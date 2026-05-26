/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.withUpdatedDefinition
import dev.groknull.bpmner.generation.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRefinementFailureException
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.BpmnValidator
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import dev.groknull.bpmner.validation.format
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Service
@Component
@Suppress("LongParameterList", "TooManyFunctions") // Spring constructor injection and cohesive repair orchestration
internal class BpmnRefinementEngine(
    private val config: BpmnConfig,
    private val bpmnRenderer: BpmnRenderer,
    private val validator: BpmnValidator,
    private val attemptRecordFactory: BpmnAttemptRecordFactory,
    private val promptFactory: BpmnRepairPromptPort,
    private val fingerprints: BpmnFingerprintService,
    private val strategies: List<BpmnRepairStrategy>,
    private val eventPublisher: ApplicationEventPublisher,
    private val defaultFlowAssigner: DefaultFlowAssigner,
    private val fidelityChecker: BpmnContractFidelityChecker,
) {
    private val contractAwareValidator = BpmnContractAwareValidator(validator, fidelityChecker)
    private val logger = LoggerFactory.getLogger(BpmnRefinementEngine::class.java)

    @Suppress("LongMethod") // repair loop; render() may throw any RuntimeException
    fun refine(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        contract: ProcessContract,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val maxEvaluations = config.maxAttempts.coerceAtLeast(1)
        // Deterministically normalise the incoming definition at the engine boundary.
        // The generator produces a raw BpmnDefinition; the engine owns all contract-aware
        // post-processing. This is the initial stamp — subsequent repair steps re-stamp below.
        val normalisedDefinition = defaultFlowAssigner.assign(contract, rendered.definition)
        val normalisedRendered = rendered.copy(definition = normalisedDefinition)
        val initialMessages = promptFactory.initialMessages(request, normalisedRendered.definition)
        val initialAttempt =
            BpmnRepairAttempt(
                attemptNumber = 1,
                repairAttempts = 0,
                graph = graph,
                evaluation =
                contractAwareValidator.evaluate(
                    graph = graph,
                    definition = normalisedRendered.definition,
                    rendered = normalisedRendered,
                    contract = contract,
                    repairAttempts = 0,
                ),
                messages = initialMessages,
            )
        val initialRecord = attemptRecordFactory.toRecord(initialAttempt)
        var state =
            RepairState(
                graph = graph,
                attempt = initialAttempt,
                record = initialRecord,
                history = BpmnAttemptHistory().append(initialRecord),
                contract = contract,
            )

        if (state.attempt.evaluation.isSuccessful()) {
            logAdvisoryDiagnostics(state.attempt.evaluation.advisoryDiagnostics, state.attempt.repairAttempts)
            context.updateProgress("Validation passed after ${state.attempt.repairAttempts} repair attempt(s)")
            val result = state.attempt.evaluation.toValidatedBpmnXml(state.attempt.repairAttempts)
            eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, state.attempt.repairAttempts))
            return result
        }

        while (state.history.size < maxEvaluations) {
            state = performRepairStep(request, state, maxEvaluations, context)
            if (state.attempt.evaluation.isSuccessful()) {
                logAdvisoryDiagnostics(state.attempt.evaluation.advisoryDiagnostics, state.attempt.repairAttempts)
                context.updateProgress("Validation passed after ${state.attempt.repairAttempts} repair attempt(s)")
                val result = state.attempt.evaluation.toValidatedBpmnXml(state.attempt.repairAttempts)
                eventPublisher.publishEvent(BpmnValidationPassedEvent(request, result.xml, state.attempt.repairAttempts))
                return result
            }
        }

        failRefinement(maxEvaluations, state.history, "exhausted BPMN repair attempts")
    }

    /**
     * Surface advisory diagnostics on a successful run. These are warning- or info-level
     * findings the pipeline accepted as documentation-grade — visible to the user but never
     * blocking. Matches bpmnlint convention (errors block, warnings advise).
     */
    private fun logAdvisoryDiagnostics(
        advisory: List<BpmnDiagnostic>,
        repairAttempts: Int,
    ) {
        if (advisory.isEmpty()) return
        logger.info(
            "Pipeline succeeded after $repairAttempts repair attempt(s) with {} advisory diagnostic(s) remaining:",
            advisory.size,
        )
        advisory.forEach { logger.info("  - {}", it.format()) }
    }

    private data class RepairState(
        val graph: LaidOutProcessGraph,
        val attempt: BpmnRepairAttempt,
        val record: BpmnAttemptRecord,
        val history: BpmnAttemptHistory,
        val contract: ProcessContract,
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

        val resolution = runRepair(currentAttempt, maxEvaluations, history, request, context)

        // Deterministically re-stamp DefaultBranch semantics. The repair LLM has no
        // explicit instruction to preserve isDefault; the assigner is the authoritative
        // contract→BPMN bridge and must run after every LLM call.
        val stamped =
            resolution.repaired.copy(
                definition = defaultFlowAssigner.assign(state.contract, resolution.repaired.definition),
            )
        val stampedResolution = resolution.copy(repaired = stamped)
        val repaired = stampedResolution.repaired

        logRouteSummary(currentAttempt, stampedResolution)

        validateRepairEffect(repaired, currentRecord, history, maxEvaluations)

        val nextGraph = state.graph.withUpdatedDefinition(repaired.definition)
        val nextAttempt = evaluateNextAttempt(nextGraph, repaired, currentAttempt, history, state.contract)
        val nextRecord =
            attemptRecordFactory.toRecord(
                attempt = nextAttempt,
                repairPromptFingerprint = fingerprints.promptFingerprint(repaired.promptText),
            )

        history = history.append(nextRecord)

        // Abort only when blocking (ERROR) diagnostics persist unchanged. Warning-only
        // sequences are allowed to repeat — they're advisory and never block success.
        // (Without this split, a single un-fixable warn-level lint rule trips the same guard
        // as a real error, which is the failure mode that motivated this redesign.)
        //
        // Compare the *blocking-only* fingerprint here. The full diagnostic fingerprint would
        // include advisory warnings, so if a blocking error is stuck but advisory warnings
        // oscillate between iterations, the full fingerprint would change every round and the
        // guard would never trip — wasting every remaining maxEvaluations attempt instead of
        // failing fast on the second detection of the stuck blocking error.
        val blockingPersistsUnchanged =
            nextAttempt.evaluation.blockingDiagnostics.isNotEmpty() &&
                nextRecord.blockingDiagnosticFingerprint == currentRecord.blockingDiagnosticFingerprint
        if (blockingPersistsUnchanged) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "unchanged blocking diagnostics after repair attempt ${nextAttempt.repairAttempts}",
            )
        }

        return RepairState(
            graph = nextGraph,
            attempt = nextAttempt,
            record = nextRecord,
            history = history,
            contract = state.contract,
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
                xml = attempt.evaluation.rendered?.xml ?: "",
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
    ): RepairStepResolution {
        val (result, localOutcome) = repairWithStrategies(attempt, request, context)
        return when (result) {
            is BpmnRepairResult.Repaired -> {
                RepairStepResolution(result, localOutcome)
            }

            is BpmnRepairResult.TerminalFailure -> {
                failRefinement(maxEvaluations, history, result.reason)
            }

            BpmnRepairResult.NotApplicable,
            is BpmnRepairResult.LocalAttemptedNoChange,
            -> {
                failRefinement(
                    maxEvaluations = maxEvaluations,
                    history = history,
                    reason = "no repair strategy produced a candidate",
                )
            }
        }
    }

    private data class RepairStepResolution(
        val repaired: BpmnRepairResult.Repaired,
        val localOutcome: BpmnLocalRepairOutcome,
    )

    private fun validateRepairEffect(
        repaired: BpmnRepairResult.Repaired,
        currentRecord: BpmnAttemptRecord,
        history: BpmnAttemptHistory,
        maxEvaluations: Int,
    ) {
        val correctedDefinitionFingerprint = fingerprints.definitionFingerprint(repaired.definition)
        if (correctedDefinitionFingerprint == currentRecord.definitionFingerprint) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "unchanged patch on repair attempt ${currentRecord.attemptNumber}",
            )
        }
        if (history.containsDefinitionFingerprint(correctedDefinitionFingerprint)) {
            failRefinement(
                maxEvaluations = maxEvaluations,
                history = history,
                reason = "repeated invalid output on repair attempt ${currentRecord.attemptNumber}",
            )
        }
    }

    private fun evaluateNextAttempt(
        graph: LaidOutProcessGraph,
        repaired: BpmnRepairResult.Repaired,
        currentAttempt: BpmnRepairAttempt,
        history: BpmnAttemptHistory,
        contract: ProcessContract,
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
            contractAwareValidator.evaluate(
                graph = graph,
                definition = repaired.definition,
                rendered = correctedRendered,
                contract = contract,
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
        request: BpmnRequest,
        operationContext: OperationContext,
    ): Pair<BpmnRepairResult, BpmnLocalRepairOutcome> {
        var localOutcome = BpmnLocalRepairOutcome.EMPTY
        for (strategy in strategies.sortedBy { it.getOrder() }) {
            val strategyContext =
                BpmnRepairStrategyContext(
                    attempt = attempt,
                    request = request,
                    operationContext = operationContext,
                    localOutcome = localOutcome,
                )
            when (val result = strategy.repair(strategyContext)) {
                BpmnRepairResult.NotApplicable -> {
                    Unit
                }

                is BpmnRepairResult.LocalAttemptedNoChange -> {
                    localOutcome =
                        BpmnLocalRepairOutcome(
                            failures = localOutcome.failures + result.outcome.failures,
                        )
                }

                else -> {
                    return result to localOutcome
                }
            }
        }
        return BpmnRepairResult.NotApplicable to localOutcome
    }

    private fun logRouteSummary(
        attempt: BpmnRepairAttempt,
        resolution: RepairStepResolution,
    ) {
        val summary = computeRouteSummary(attempt.diagnostics, resolution)
        if (summary.total == 0) return
        logger.info(
            "Repair attempt {} route summary: total={} localAttempted={} localApplied={} " +
                "localFailed={} llmRouted={} unfixable={}",
            attempt.attemptNumber,
            summary.total,
            summary.localAttempted,
            summary.localApplied,
            summary.localFailed,
            summary.llmRouted,
            summary.unfixable,
        )
    }

    @Suppress("DEPRECATION") // TODO(Phase 3): drop LOCAL_XML_FIX branch with the enum value
    private fun computeRouteSummary(
        diagnostics: List<BpmnDiagnostic>,
        resolution: RepairStepResolution,
    ): RouteSummary {
        val lintDiagnostics = diagnostics.filter { it.source == BpmnDiagnosticSource.LINT }
        var localAttempted = 0
        var llmRouted = 0
        var unfixable = 0
        for (diagnostic in lintDiagnostics) {
            when (diagnostic.kind) {
                RepairKind.LOCAL_MODEL_FIX, RepairKind.LOCAL_XML_FIX -> localAttempted++
                RepairKind.LLM_MODEL_PATCH, RepairKind.LLM_XML_REWRITE, null -> llmRouted++
                RepairKind.UNFIXABLE -> unfixable++
            }
        }
        val localApplied = resolution.repaired.localFixSummary?.total ?: 0
        val localFailed = resolution.localOutcome.failures.size
        return RouteSummary(
            total = lintDiagnostics.size,
            localAttempted = localAttempted,
            localApplied = localApplied,
            localFailed = localFailed,
            llmRouted = llmRouted,
            unfixable = unfixable,
        )
    }

    private data class RouteSummary(
        val total: Int,
        val localAttempted: Int,
        val localApplied: Int,
        val localFailed: Int,
        val llmRouted: Int,
        val unfixable: Int,
    )

    private fun failRefinement(
        maxEvaluations: Int,
        history: BpmnAttemptHistory,
        reason: String,
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
