package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnLocalRepairOutcome
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.core.RepairKind
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class LlmPatchRepairStrategy(
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
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
    private val promptFactory: BpmnRepairPromptPort,
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

private fun eligibleForLlm(
    diagnostic: BpmnDiagnostic,
    localOutcome: BpmnLocalRepairOutcome,
): Boolean {
    val kind = diagnostic.kind
    val routedToLlm = kind == null || kind == RepairKind.LLM_MODEL_PATCH || kind == RepairKind.LLM_XML_REWRITE
    val failedLocally = localOutcome.matches(diagnostic) != null
    return routedToLlm || failedLocally
}
