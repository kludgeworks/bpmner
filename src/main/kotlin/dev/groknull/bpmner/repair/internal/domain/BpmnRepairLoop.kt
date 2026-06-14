/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.workflow.loop.RepeatUntilAcceptableBuilder
import com.embabel.agent.api.common.workflow.loop.TextFeedback
import com.embabel.agent.core.ReplanRequestedException
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRepairScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Encapsulates the [RepeatUntilAcceptable] loop that drives cost-aware repair:
 * deterministic local fixes first, then LLM label patches, then structural patches, then
 * full rewrites.  The loop exits clean (score = 1.0) or exhausts [maxIterations] and
 * returns the best evaluation reached so far.
 *
 * This is **wiring only** — the repair handlers, advancer, appliers, and validator are all
 * reused unchanged (doc ADR-6 / N1).
 */
@Component
internal class BpmnRepairLoop(
    private val localFixApplier: BpmnLocalFixApplier,
    private val llmRepairApplier: BpmnLlmRepairApplier,
    private val config: BpmnConfig,
) {
    private val logger = LoggerFactory.getLogger(BpmnRepairLoop::class.java)

    /**
     * Run the repair loop starting from [seed].  Returns [seed] immediately when it is
     * already diagnostics-clean (no-op fast path, preserving the happy-path pipeline).
     *
     * The [context] is the enclosing `validate` [ActionContext]; it is threaded into the
     * LLM appliers so they can obtain a [com.embabel.agent.api.common.PromptRunner].
     */
    fun run(seed: BpmnRepairEvaluation, context: ActionContext): BpmnRepairEvaluation {
        if (seed.diagnosticsResolved) {
            logger.debug("Repair loop: seed already clean — skipping")
            return seed
        }

        val maxIterations = config.budget.maxRepairIterations
        logger.debug(
            "Repair loop: starting with {} blocking diagnostic(s), max {} iteration(s)",
            seed.evaluation.blockingDiagnostics.size,
            maxIterations,
        )

        val scope = RepeatUntilAcceptableBuilder
            .returning(BpmnRepairEvaluation::class.java)
            .consuming(ReadyBpmnContext::class.java)
            .withMaxIterations(maxIterations)
            .withScoreThreshold(1.0)
            .repeating { ctx ->
                // On the first iteration lastAttempt() is null; use the captured seed.
                val prior = ctx.lastAttempt()?.result ?: seed
                selectAndApply(prior, context)
            }
            .withEvaluator { ctx ->
                evaluate(ctx.resultToEvaluate)
            }
            .build()

        return scope.asSubProcess(context, BpmnRepairEvaluation::class.java)
    }

    /**
     * Apply the single cheapest applicable fix tier to [prior] and return the next
     * [BpmnRepairEvaluation].  When a no-progress / stuck replan signal fires from the
     * applier, return [prior] unchanged so the evaluator can reject it and the loop
     * advances towards the iteration bound (doc §3.4, approach 2).
     */
    private fun selectAndApply(prior: BpmnRepairEvaluation, context: ActionContext): BpmnRepairEvaluation {
        return try {
            when {
                prior.hasLocalFixable ->
                    localFixApplier.applyLocalModelFix(prior)

                prior.hasLlmLabelEligible ->
                    llmRepairApplier.applyLlmLabelPatch(prior, context, labelCandidates(prior))

                prior.hasLlmStructuralEligible ->
                    llmRepairApplier.applyLlmStructuralPatch(prior, context, structuralCandidates(prior))

                prior.hasLlmEligible ->
                    llmRepairApplier.applyFullLlmRewrite(prior, context, prior.diagnostics)

                else -> prior // nothing applicable; evaluator rejects, loop ends
            }
        } catch (e: ReplanRequestedException) {
            // No-progress / stuck / malformed-LLM guard fired.  Return prior unchanged so the
            // evaluator scores it non-accepting and the iteration bound terminates the loop
            // (doc §3.4 approach 2: catch at the loop seam, do not weaken the guards).
            logger.debug("Repair loop: replan signal caught — returning prior to advance iteration bound. Reason: {}", e.message)
            prior
        }
    }

    /** Convert a [BpmnRepairEvaluation] into a [TextFeedback] score for the evaluator. */
    private fun evaluate(result: BpmnRepairEvaluation): TextFeedback {
        val blocking = result.evaluation.blockingDiagnostics
        val score = when {
            result.evaluation.isSuccessful() -> 1.0
            blocking.isEmpty() -> NEAR_CLEAN_SCORE // validated but edge case — treat as near-clean
            else -> maxOf(0.0, 1.0 - blocking.size.toDouble() / (blocking.size + 1))
        }
        val feedback = if (blocking.isEmpty()) {
            "No blocking diagnostics"
        } else {
            blocking.take(MAX_FEEDBACK_DIAGNOSTICS).joinToString("; ") { d ->
                d.rule?.let { "[${d.elementId ?: "?"}] $it" } ?: (d.elementId ?: "unknown diagnostic")
            }
        }
        return TextFeedback(score, feedback)
    }

    private fun labelCandidates(eval: BpmnRepairEvaluation): List<BpmnDiagnostic> {
        return eval.diagnostics.filter { it.kind != RepairKind.UNFIXABLE && it.repairScope == BpmnRepairScope.LABEL }
    }

    private fun structuralCandidates(eval: BpmnRepairEvaluation): List<BpmnDiagnostic> {
        return eval.diagnostics.filter { d ->
            d.kind != RepairKind.UNFIXABLE &&
                (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
        }
    }

    private companion object {
        const val MAX_FEEDBACK_DIAGNOSTICS = 5
        const val NEAR_CLEAN_SCORE = 0.9
    }
}
