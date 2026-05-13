package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.core.BpmnAttemptHistory
import dev.groknull.bpmner.core.BpmnAttemptRecord
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnFingerprintService
import dev.groknull.bpmner.core.BpmnRefinementFailureException
import dev.groknull.bpmner.core.BpmnRepairAttempt
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.withUpdatedDefinition
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.BpmnValidator
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Service
@Component
@Suppress("LongParameterList") // Spring constructor injection — no cleaner grouping possible
internal class BpmnRefinementEngine(
    private val config: BpmnConfig,
    private val bpmnRenderer: BpmnRenderer,
    private val validator: BpmnValidator,
    private val promptFactory: BpmnRepairPromptFactory,
    private val fingerprints: BpmnFingerprintService,
    private val strategies: List<BpmnRepairStrategy>,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnRefinementEngine::class.java)

    @Suppress("LongMethod") // repair loop; render() may throw any RuntimeException
    fun refine(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val maxEvaluations = config.maxAttempts.coerceAtLeast(1)
        val initialMessages = promptFactory.initialMessages(request, rendered.definition)
        val initialAttempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = graph,
                evaluation =
                    validator.evaluate(
                        graph = graph,
                        definition = rendered.definition,
                        rendered = rendered,
                        repairAttempts = 0,
                    ),
                messages = initialMessages,
            )
        val initialRecord = validator.toRecord(initialAttempt)
        var state =
            RepairState(
                graph = graph,
                attempt = initialAttempt,
                record = initialRecord,
                history = BpmnAttemptHistory().append(initialRecord),
            )

        if (state.attempt.evaluation.isSuccessful()) {
            context.updateProgress("Validation passed after ${state.attempt.repairAttempts} repair attempt(s)")
            val result = state.attempt.evaluation.toValidatedBpmnXml(state.attempt.repairAttempts)
            eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, state.attempt.repairAttempts))
            return result
        }

        while (state.history.size < maxEvaluations) {
            state = performRepairStep(request, state, maxEvaluations, context)
            if (state.attempt.evaluation.isSuccessful()) {
                context.updateProgress("Validation passed after ${state.attempt.repairAttempts} repair attempt(s)")
                val result = state.attempt.evaluation.toValidatedBpmnXml(state.attempt.repairAttempts)
                eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, state.attempt.repairAttempts))
                return result
            }
        }

        failRefinement(maxEvaluations, state.history, "exhausted BPMN repair attempts", request)
    }

    private data class RepairState(
        val graph: LaidOutProcessGraph,
        val attempt: BpmnRepairAttempt,
        val record: BpmnAttemptRecord,
        val history: BpmnAttemptHistory,
    )

    private fun performRepairStep(
        request: BpmnRequest,
        state: RepairState,
        maxEvaluations: Int,
        context: ActionContext,
    ): RepairState {
        val currentAttempt = state.attempt
        val currentRecord = state.record
        var history = state.history

        logAndPublishEvent(request, currentAttempt, maxEvaluations, context)

        val repaired = runRepair(currentAttempt, maxEvaluations, history, request, context)

        validateRepairEffect(repaired, currentRecord, history, maxEvaluations, request)

        val nextGraph = state.graph.withUpdatedDefinition(repaired.definition)
        val nextAttempt = evaluateNextAttempt(nextGraph, repaired, currentAttempt, history)
        val nextRecord =
            validator.toRecord(
                attempt = nextAttempt,
                repairPromptFingerprint = fingerprints.promptFingerprint(repaired.promptText),
            )

        history = history.append(nextRecord)

        if (!nextAttempt.evaluation.isSuccessful() &&
            nextRecord.diagnosticFingerprint == currentRecord.diagnosticFingerprint
        ) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "unchanged diagnostics after repair attempt ${nextAttempt.repairAttempts}",
                request = request,
            )
        }

        return RepairState(
            graph = nextGraph,
            attempt = nextAttempt,
            record = nextRecord,
            history = history,
        )
    }

    private fun logAndPublishEvent(
        request: BpmnRequest,
        attempt: BpmnRepairAttempt,
        maxEvaluations: Int,
        context: ActionContext,
    ) {
        validator.logDiagnosticSummary(attempt.evaluation.globalDiagnostics.diagnostics)
        val globalFeedbackDiagnostics = attempt.evaluation.globalDiagnostics
        context.updateProgress(
            "Repair attempt ${attempt.repairAttempts + 1}/${maxEvaluations - 1}: " +
                "graph=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.GRAPH)}, " +
                "xsd=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.XSD)}, " +
                "lint=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.LINT)}",
        )

        eventPublisher.publishEvent(
            BpmnValidationFailedEvent(
                request = request,
                diagnostics = attempt.diagnostics,
                attemptNumber = attempt.attemptNumber,
                repairAttempts = attempt.repairAttempts,
            ),
        )
    }

    private fun runRepair(
        attempt: BpmnRepairAttempt,
        maxEvaluations: Int,
        history: BpmnAttemptHistory,
        request: BpmnRequest,
        context: ActionContext,
    ): BpmnRepairResult.Repaired {
        val repairPromptRunner =
            promptRunner(context, request).let { runner ->
                val docsPrompt = promptFactory.lintRuleDocsPrompt(attempt.diagnostics)
                if (docsPrompt != null) runner.withPromptContributor(docsPrompt) else runner
            }
        return when (val repair = repairWithStrategies(attempt, repairPromptRunner)) {
            is BpmnRepairResult.Repaired -> {
                repair
            }

            is BpmnRepairResult.TerminalFailure -> {
                failRefinement(maxEvaluations, history, repair.reason, request)
            }

            BpmnRepairResult.NotApplicable -> {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "no repair strategy produced a candidate",
                    request = request,
                )
            }
        }
    }

    private fun validateRepairEffect(
        repaired: BpmnRepairResult.Repaired,
        currentRecord: BpmnAttemptRecord,
        history: BpmnAttemptHistory,
        maxEvaluations: Int,
        request: BpmnRequest,
    ) {
        val correctedDefinitionFingerprint = fingerprints.definitionFingerprint(repaired.definition)
        if (correctedDefinitionFingerprint == currentRecord.definitionFingerprint) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "unchanged patch on repair attempt ${currentRecord.attemptNumber}",
                request = request,
            )
        }
        if (history.containsDefinitionFingerprint(correctedDefinitionFingerprint)) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "repeated invalid output on repair attempt ${currentRecord.attemptNumber}",
                request = request,
            )
        }
    }

    private fun evaluateNextAttempt(
        graph: LaidOutProcessGraph,
        repaired: BpmnRepairResult.Repaired,
        currentAttempt: BpmnRepairAttempt,
        history: BpmnAttemptHistory,
    ): BpmnRepairAttempt {
        var renderFailureMessage: String? = null
        val correctedRendered =
            try {
                bpmnRenderer.render(graph)
            } catch (e: IllegalStateException) {
                renderFailureMessage = e.message ?: e.javaClass.simpleName
                null
            } catch (e: IllegalArgumentException) {
                renderFailureMessage = e.message ?: e.javaClass.simpleName
                null
            }

        val nextEvaluation =
            validator.evaluate(
                graph = graph,
                definition = repaired.definition,
                rendered = correctedRendered,
                renderFailureMessage = renderFailureMessage,
                repairAttempts = currentAttempt.repairAttempts + 1,
            )

        return BpmnRepairAttempt(
            attemptNumber = history.size + 1,
            repairAttempts = currentAttempt.repairAttempts + 1,
            graph = graph,
            evaluation = nextEvaluation,
            messages = repaired.messages,
        )
    }

    private fun repairWithStrategies(
        attempt: BpmnRepairAttempt,
        promptRunner: PromptRunner,
    ): BpmnRepairResult {
        val strategyContext =
            BpmnRepairStrategyContext(
                attempt = attempt,
                promptRunner = promptRunner,
            )
        for (strategy in strategies) {
            when (val result = strategy.repair(strategyContext)) {
                BpmnRepairResult.NotApplicable -> Unit
                else -> return result
            }
        }
        return BpmnRepairResult.NotApplicable
    }

    private fun promptRunner(
        context: OperationContext,
        request: BpmnRequest,
    ): PromptRunner = config.repairer.promptRunner(context).withPromptContributor(request)

    @Suppress("UnusedParameter") // request reserved for future error-context enrichment
    private fun failRefinement(
        maxEvaluations: Int,
        history: BpmnAttemptHistory,
        reason: String,
        request: BpmnRequest,
    ): Nothing {
        val compactHistory = history.compact()

        val fingerprintRuns = mutableMapOf<String, MutableList<Int>>()
        for (record in history.records) {
            fingerprintRuns.getOrPut(record.diagnosticFingerprint) { mutableListOf() } += record.attemptNumber
        }
        val stuckFingerprints = fingerprintRuns.filterValues { it.size > 1 }
        val lastStuckRecord = history.records.lastOrNull { stuckFingerprints.containsKey(it.diagnosticFingerprint) }
        val lastRecord = history.last

        val summary =
            buildString {
                appendLine("BPMN generation failed after ${history.size} attempt(s): $reason")
                appendLine("  Cause: $reason")
                appendLine("  Attempts: ${history.size} / $maxEvaluations")
                for ((fp, attempts) in stuckFingerprints) {
                    appendLine(
                        "  Recurring (stuck) fingerprint: $fp" +
                            " — seen in attempt(s): ${attempts.joinToString(", ")}",
                    )
                }
                val displayRecord = lastStuckRecord ?: lastRecord
                if (displayRecord != null && displayRecord.topDiagnostics.isNotEmpty()) {
                    val label = if (lastStuckRecord != null) "Top stuck diagnostics" else "Last attempt diagnostics"
                    appendLine("  $label:")
                    displayRecord.topDiagnostics.forEach { appendLine("    - $it") }
                }
                if (lastRecord != null) {
                    appendLine(
                        "  Last attempt: graph=${lastRecord.graphDiagnostics}," +
                            " render=${lastRecord.renderDiagnostics}," +
                            " xsd=${lastRecord.xsdDiagnostics}," +
                            " lint=${lastRecord.lintDiagnostics}," +
                            " def=${lastRecord.definitionFingerprint}",
                    )
                }
                append("  Full history: $compactHistory")
            }.trim()

        logger.error(summary)
        throw BpmnRefinementFailureException(
            message = "Failed to produce valid BPMN after $maxEvaluations attempts: $reason; history=$compactHistory",
            summary = summary,
        )
    }
}
