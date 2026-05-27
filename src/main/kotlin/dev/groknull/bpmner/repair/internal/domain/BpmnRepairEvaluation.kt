/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.Message
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnRepairScope

/**
 * The shared blackboard threaded through every `@Action` of [BpmnRepairAgent] via
 * `outputBinding = "repairEval"` + `@RequireNameMatch`. Replaces the imperative
 * `RepairState` formerly held inside `BpmnRefinementEngine.refine()`.
 *
 * Each repair action receives the previous evaluation, produces a repaired definition
 * (via local handlers or an LLM call), then calls `revalidateAndAdvance()` on the agent
 * to re-render + re-validate + roll the history forward. The result is a fresh
 * `BpmnRepairEvaluation` the planner threads into the next chosen action.
 *
 * The `@Condition` methods on the agent inspect the latest `diagnostics` field to decide
 * which action(s) are applicable next. GOAP's cost-based A* selection picks the cheapest
 * applicable one each iteration.
 */
internal data class BpmnRepairEvaluation(
    val request: BpmnRequest,
    val graph: LaidOutProcessGraph,
    val rendered: RenderedBpmn?,
    val evaluation: BpmnEvaluation,
    val messages: List<Message>,
    val history: BpmnAttemptHistory,
    val contract: ProcessContract,
    val repairAttempts: Int,
    val renderFailureMessage: String? = null,
) {
    val definition: BpmnDefinition
        get() = evaluation.definition

    val diagnostics: List<BpmnDiagnostic>
        get() = evaluation.diagnostics

    // -----------------------------------------------------------------------------------
    // Convenience predicates used by the GOAP `@Condition` methods on `BpmnRepairAgent`.
    // Moved here so the condition signatures stay short enough to satisfy both ktlint
    // (which inlines the body) and detekt's `MaxLineLength=130` (which rejects the inlined
    // form when the body is non-trivial).

    val hasDiagnostics: Boolean
        get() = diagnostics.isNotEmpty()

    val diagnosticsResolved: Boolean
        get() = diagnostics.isEmpty()

    val hasLocalFixable: Boolean
        get() = diagnostics.any { it.kind == RepairKind.LOCAL_MODEL_FIX }

    val hasLlmEligible: Boolean
        get() = diagnostics.any { it.kind != RepairKind.UNFIXABLE }

    /**
     * Phase 4 review G1: scope-specific eligibility splits so the planner distinguishes
     * `applyLlmLabelPatch` and `applyLlmStructuralPatch` at plan time. Without this, A*
     * picks the cheaper label action on structural-only repairs, immediately throws
     * `ReplanRequestedException`, and burns a budget action per iteration.
     */
    val hasLlmLabelEligible: Boolean
        get() = diagnostics.any { it.kind != RepairKind.UNFIXABLE && it.repairScope == BpmnRepairScope.LABEL }

    val hasLlmStructuralEligible: Boolean
        get() = diagnostics.any { d ->
            d.kind != RepairKind.UNFIXABLE &&
                (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
        }

    /**
     * Project the blackboard into a [BpmnRepairAttempt] — the existing prompt/patch surface
     * consumes this shape, so the LLM-repair actions reuse the legacy `BpmnRepairPromptPort`
     * and `BpmnPatchApplicationPort` calls without re-modelling.
     */
    fun toAttempt(): BpmnRepairAttempt = BpmnRepairAttempt(
        attemptNumber = history.size + 1,
        repairAttempts = repairAttempts,
        graph = graph,
        evaluation = evaluation,
        messages = messages,
    )
}
