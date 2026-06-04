/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.common.ActionContext
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.internal.domain.BpmnContractAwareValidator
import dev.groknull.bpmner.repair.internal.domain.BpmnLlmRepairApplier
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalFixApplier
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairAdvancer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairEvaluation
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * Phase 4 (#219): repair-loop GOAP decomposition.
 *
 * The agent presents six `@Action`s and four `@Condition`s; Embabel's planner chains them
 * via `outputBinding = "repairEval"` so every action consumes and produces the same
 * [BpmnRepairEvaluation] blackboard. Cost-based A* picks the cheapest applicable action
 * each iteration: deterministic local fixes first (0.1), then label-scope LLM patches
 * (0.5), then structural LLM patches (0.7), then a full LLM rewrite (0.9). Each repair
 * action has `canRerun = true` so the planner can loop the same action while it keeps
 * making progress, and replan into the next cost tier when it can't.
 *
 * Failure modes are surfaced via the framework's status-typed exceptions:
 *  - `ProcessExecutionStuckException` — the planner found no applicable action.
 *  - `ProcessExecutionTerminatedException` — `Budget(actions = N)` exhausted before a goal
 *    was reached. Repeated no-progress (same fingerprint twice in a row) flips through
 *    `ReplanRequestedException` which costs an action each iteration; budget exhaustion is
 *    therefore the natural ceiling on stuck-but-not-stuck loops.
 *
 * Replaces the imperative `BpmnRefinementEngine.refine()` while-loop and the four
 * `BpmnRepairStrategy` implementations — see #219 for the side-by-side architecture table.
 */
@Application
@Agent(
    description = "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
)
internal class BpmnRepairAgent(
    private val advancer: BpmnRepairAdvancer,
    private val localFixApplier: BpmnLocalFixApplier,
    private val llmRepairApplier: BpmnLlmRepairApplier,
    private val contractAwareValidator: BpmnContractAwareValidator,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnRepairAgent::class.java)

    // -----------------------------------------------------------------------------------
    // Actions

    /**
     * Initial validation — normalise the incoming definition (default-flow re-stamp), seed
     * the LLM message chain, and produce the first [BpmnRepairEvaluation] on the blackboard.
     * Cost is 0 so the planner always runs this first when GOAP needs a fresh blackboard.
     */
    @Action(
        post = ["hasDiagnostics", "diagnosticsResolved"],
        outputBinding = "repairEval",
    )
    fun validate(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        validatedContract: ValidatedProcessContract,
    ): BpmnRepairEvaluation {
        return advancer.initialEvaluation(ready, graph, rendered, validatedContract)
    }

    /**
     * Local deterministic fixes — dispatch any `LOCAL_MODEL_FIX` diagnostic to its declared
     * handler. Cheapest repair tier (0.1); always runs before any LLM call when applicable.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLocalFixable"],
        post = ["diagnosticsResolved"],
        cost = COST_DETERMINISTIC,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyDeterministicFixes(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        return localFixApplier.applyLocalModelFix(repairEval)
    }

    /**
     * LLM label-scope patch — fixes `BpmnRepairScope.LABEL` diagnostics via the
     * `labelRepairer` actor.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmLabelEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_LABEL,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyLlmLabelPatch(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        val candidates = repairEval.diagnostics.filter {
            eligibleForLlm(it) && it.repairScope == BpmnRepairScope.LABEL
        }
        return llmRepairApplier.applyLlmLabelPatch(repairEval, context, candidates)
    }

    /**
     * LLM structural patch — fixes `OUTLINE` / `PHASE`-scope diagnostics via the
     * `patchRepairer` actor.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmStructuralEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_STRUCTURAL,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyLlmStructuralPatch(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        val candidates = repairEval.diagnostics.filter {
            eligibleForLlm(it) && (it.repairScope == BpmnRepairScope.OUTLINE || it.repairScope == BpmnRepairScope.PHASE)
        }
        return llmRepairApplier.applyLlmStructuralPatch(repairEval, context, candidates)
    }

    /**
     * Full LLM rewrite — produces a fresh `BpmnDefinition` rather than a patch. Highest-cost
     * tier; only chosen when patch-scope repairs cannot resolve the remaining diagnostics.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_REWRITE,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyFullLlmRewrite(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        val candidates = repairEval.diagnostics.filter { eligibleForLlm(it) }
        return llmRepairApplier.applyFullLlmRewrite(repairEval, context, candidates)
    }

    /**
     * Terminal action — package the validated XML and publish the success event.
     */
    @Action(pre = ["diagnosticsResolved"])
    @AchievesGoal(
        description = "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
        export = Export(
            name = "repairBpmn",
            remote = true,
            startingInputTypes = [
                BpmnRequest::class,
                LaidOutProcessGraph::class,
                RenderedBpmn::class,
                ValidatedProcessContract::class,
            ],
        ),
    )
    fun finalize(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): ValidatedBpmnXml {
        logAdvisoryDiagnostics(repairEval.evaluation.advisoryDiagnostics, repairEval.repairAttempts)
        val xml = repairEval.evaluation.validatedXml
            ?: repairEval.rendered?.xml
            ?: error("finalize fired with no validated XML and no rendered XML on the blackboard")
        val result = ValidatedBpmnXml(
            definition = repairEval.definition,
            xml = xml,
            diagnostics = repairEval.diagnostics,
            repairAttempts = repairEval.repairAttempts,
        )
        eventPublisher.publishEvent(
            BpmnValidationPassedEvent(repairEval.request, result.xml, repairEval.repairAttempts),
        )
        return result
    }

    // -----------------------------------------------------------------------------------
    // Conditions — every `repairEval` parameter carries `@RequireNameMatch` so all bindings
    // resolve to the same blackboard instance the actions produced.

    @Condition
    fun hasDiagnostics(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasDiagnostics

    @Condition
    fun diagnosticsResolved(@RequireNameMatch("repairEval") repair: BpmnRepairEvaluation): Boolean = repair.diagnosticsResolved

    @Condition
    fun hasLocalFixable(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasLocalFixable

    @Condition
    fun hasLlmEligible(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasLlmEligible

    @Condition
    fun hasLlmLabelEligible(@RequireNameMatch("repairEval") repair: BpmnRepairEvaluation): Boolean = repair.hasLlmLabelEligible

    @Condition
    fun hasLlmStructuralEligible(@RequireNameMatch("repairEval") r: BpmnRepairEvaluation): Boolean = r.hasLlmStructuralEligible

    // -----------------------------------------------------------------------------------
    // Helpers

    private fun publishAttemptEvent(repairEval: BpmnRepairEvaluation) {
        contractAwareValidator.logDiagnosticSummary(repairEval.evaluation.globalDiagnostics.diagnostics)
        eventPublisher.publishEvent(
            BpmnValidationFailedEvent(
                request = repairEval.request,
                xml = repairEval.rendered?.xml ?: "",
                diagnostics = repairEval.diagnostics,
                attemptNumber = repairEval.history.size,
                repairAttempts = repairEval.repairAttempts,
            ),
        )
    }

    private fun logAdvisoryDiagnostics(advisory: List<BpmnDiagnostic>, repairAttempts: Int) {
        if (advisory.isEmpty()) return
        logger.info(
            "Pipeline succeeded after {} repair attempt(s) with {} advisory diagnostic(s) remaining",
            repairAttempts,
            advisory.size,
        )
    }

    private fun eligibleForLlm(diagnostic: BpmnDiagnostic): Boolean = diagnostic.kind != RepairKind.UNFIXABLE

    private companion object {
        const val COST_DETERMINISTIC = 0.1
        const val COST_LLM_LABEL = 0.5
        const val COST_LLM_STRUCTURAL = 0.7
        const val COST_LLM_REWRITE = 0.9
    }
}
