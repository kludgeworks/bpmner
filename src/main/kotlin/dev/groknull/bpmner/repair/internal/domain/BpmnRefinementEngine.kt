@file:Suppress(
    "CyclomaticComplexMethod",
    "ForbiddenComment",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "SpreadOperator",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnusedParameter",
    "UnusedPrivateProperty",
)

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.core.BpmnAttemptHistory
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
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.BpmnValidator
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnRefinementEngine(
    private val config: BpmnConfig,
    private val bpmnRenderer: BpmnRenderer,
    private val validator: BpmnValidator,
    private val promptFactory: dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory,
    private val fingerprints: BpmnFingerprintService,
    private val strategies: List<BpmnRepairStrategy>,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnRefinementEngine::class.java)

    fun refine(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val maxEvaluations = config.maxAttempts.coerceAtLeast(1)
        val initialMessages = promptFactory.initialMessages(request, rendered.definition)
        var currentGraph = graph
        var currentAttempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = currentGraph,
                evaluation =
                    validator.evaluate(
                        graph = currentGraph,
                        definition = rendered.definition,
                        rendered = rendered,
                        repairAttempts = 0,
                    ),
                messages = initialMessages,
            )
        var currentRecord = validator.toRecord(currentAttempt)
        var history = BpmnAttemptHistory().append(currentRecord)
        if (currentAttempt.evaluation.isSuccessful()) {
            context.updateProgress("Validation passed after ${currentAttempt.repairAttempts} repair attempt(s)")
            val result = currentAttempt.evaluation.toValidatedBpmnXml(currentAttempt.repairAttempts)
            eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, currentAttempt.repairAttempts))
            return result
        }

        while (history.size < maxEvaluations) {
            validator.logDiagnosticSummary(currentAttempt.evaluation.globalDiagnostics.diagnostics)
            val globalFeedbackDiagnostics = currentAttempt.evaluation.globalDiagnostics
            context.updateProgress(
                "Repair attempt ${currentAttempt.repairAttempts + 1}/${maxEvaluations - 1}: " +
                    "graph=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.GRAPH)}, " +
                    "xsd=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.XSD)}, " +
                    "lint=${globalFeedbackDiagnostics.countFor(BpmnDiagnosticSource.LINT)}",
            )

            eventPublisher.publishEvent(
                BpmnValidationFailedEvent(
                    request = request,
                    diagnostics = currentAttempt.diagnostics,
                    attemptNumber = currentAttempt.attemptNumber,
                    repairAttempts = currentAttempt.repairAttempts,
                ),
            )

            val repairPromptRunner =
                promptRunner(context, request).let { runner ->
                    val docsPrompt = promptFactory.lintRuleDocsPrompt(currentAttempt.diagnostics)
                    if (docsPrompt != null) runner.withPromptContributor(docsPrompt) else runner
                }
            val repair = repairWithStrategies(currentAttempt, repairPromptRunner)
            val repaired =
                when (repair) {
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

            val correctedDefinitionFingerprint = fingerprints.definitionFingerprint(repaired.definition)
            if (correctedDefinitionFingerprint == currentRecord.definitionFingerprint) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "unchanged patch on repair attempt ${currentAttempt.repairAttempts + 1}",
                    request = request,
                )
            }
            if (history.containsDefinitionFingerprint(correctedDefinitionFingerprint)) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "repeated invalid output on repair attempt ${currentAttempt.repairAttempts + 1}",
                    request = request,
                )
            }

            currentGraph = currentGraph.withUpdatedDefinition(repaired.definition)
            var renderFailureMessage: String? = null
            val correctedRendered =
                try {
                    bpmnRenderer.render(currentGraph)
                } catch (e: Exception) {
                    renderFailureMessage = e.message ?: e.javaClass.simpleName
                    null
                }
            val nextEvaluation =
                validator.evaluate(
                    graph = currentGraph,
                    definition = repaired.definition,
                    rendered = correctedRendered,
                    renderFailureMessage = renderFailureMessage,
                    repairAttempts = currentAttempt.repairAttempts + 1,
                )
            val nextAttempt =
                BpmnRepairAttempt(
                    attemptNumber = history.size + 1,
                    repairAttempts = currentAttempt.repairAttempts + 1,
                    graph = currentGraph,
                    evaluation = nextEvaluation,
                    messages = repaired.messages,
                )
            val nextRecord =
                validator.toRecord(
                    attempt = nextAttempt,
                    repairPromptFingerprint = fingerprints.promptFingerprint(repaired.promptText),
                )
            history = history.append(nextRecord)
            if (nextAttempt.evaluation.isSuccessful()) {
                context.updateProgress("Validation passed after ${nextAttempt.repairAttempts} repair attempt(s)")
                val result = nextAttempt.evaluation.toValidatedBpmnXml(nextAttempt.repairAttempts)
                eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, nextAttempt.repairAttempts))
                return result
            }
            if (nextRecord.diagnosticFingerprint == currentRecord.diagnosticFingerprint) {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "unchanged diagnostics after repair attempt ${nextAttempt.repairAttempts}",
                    request = request,
                )
            }

            currentAttempt = nextAttempt
            currentRecord = nextRecord
        }

        failRefinement(
            maxEvaluations = maxEvaluations,
            history = history,
            reason = "exhausted BPMN repair attempts",
            request = request,
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
                    appendLine("  Recurring (stuck) fingerprint: $fp — seen in attempt(s): ${attempts.joinToString(", ")}")
                }
                val displayRecord = lastStuckRecord ?: lastRecord
                if (displayRecord != null && displayRecord.topDiagnostics.isNotEmpty()) {
                    val label = if (lastStuckRecord != null) "Top stuck diagnostics" else "Last attempt diagnostics"
                    appendLine("  $label:")
                    displayRecord.topDiagnostics.forEach { appendLine("    - $it") }
                }
                if (lastRecord != null) {
                    appendLine(
                        "  Last attempt: graph=${lastRecord.graphDiagnostics}, render=${lastRecord.renderDiagnostics}, " +
                            "xsd=${lastRecord.xsdDiagnostics}, lint=${lastRecord.lintDiagnostics}, " +
                            "def=${lastRecord.definitionFingerprint}",
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
