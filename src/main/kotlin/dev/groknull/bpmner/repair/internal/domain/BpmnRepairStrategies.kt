package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnLocalRepairOutcome
import dev.groknull.bpmner.core.BpmnRepairRoute
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class LlmPatchRepairStrategy(
    private val promptFactory: BpmnRepairPromptFactory,
    private val patchApplier: BpmnPatchApplier,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 200

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) &&
                    (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)
        val patch = context.promptRunner.createObject(feedback, BpmnRepairPatch::class.java)

        return when (val application = patchApplier.apply(context.attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                BpmnRepairResult.Repaired(
                    definition = application.definition,
                    promptText = feedback,
                    messages =
                        context.attempt.messages + UserMessage(feedback) +
                            AssistantMessage(patch.toString()),
                )
            }

            is PatchApplicationResult.Failure -> {
                LoggerFactory
                    .getLogger(this::class.java)
                    .warn("LLM patch application failed, falling back: {}", application.reason)
                BpmnRepairResult.NotApplicable
            }

            PatchApplicationResult.NoOp -> {
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

@Service
@Component
internal class FullLlmRewriteRepairStrategy(
    private val promptFactory: BpmnRepairPromptFactory,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 300

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.fullRepairFeedback(context.attempt, candidates, context.localOutcome)
        val repaired = context.promptRunner.createObject(feedback, BpmnDefinition::class.java)

        return BpmnRepairResult.Repaired(
            definition = repaired,
            promptText = feedback,
            messages =
                context.attempt.messages + UserMessage(feedback) + AssistantMessage(repaired.toString()),
        )
    }
}

@Service
@Component
internal class DeterministicTopologyRepairStrategy(
    private val topologyRepair: BpmnTopologyRepair,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 50

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val result = topologyRepair.repair(context.attempt.definition, context.attempt.diagnostics)
        return when (result) {
            is PatchApplicationResult.Success -> {
                BpmnRepairResult.Repaired(
                    definition = result.definition,
                    promptText = "Deterministic topology repair",
                    messages = context.attempt.messages,
                )
            }

            else -> {
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

private fun eligibleForLlm(
    diagnostic: BpmnDiagnostic,
    localOutcome: BpmnLocalRepairOutcome,
): Boolean {
    val route = diagnostic.repairRoute
    val routedToLlm = route == BpmnRepairRoute.LLM || route == null
    val failedLocally = localOutcome.matches(diagnostic) != null
    return routedToLlm || failedLocally
}
