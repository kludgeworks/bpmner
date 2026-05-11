package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnRepairPromptFactory
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.stereotype.Component

@Service
@Component
internal class TargetedLabelRepairStrategy(
    private val config: BpmnConfig,
    private val promptFactory: BpmnRepairPromptFactory,
    private val patchApplier: BpmnPatchApplier,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 100

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val labelDiagnostics =
            context.attempt.evaluation.diagnostics.filter {
                it.source == BpmnDiagnosticSource.LINT && it.rule?.contains("name") == true
            }
        if (labelDiagnostics.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.targetedLabelPatchFeedback(context.attempt.definition, labelDiagnostics)
        val patch = context.promptRunner.createObject(feedback, BpmnRepairPatch::class.java)

        return when (val application = patchApplier.apply(context.attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                BpmnRepairResult.Repaired(
                    definition = application.definition,
                    promptText = feedback,
                    messages =
                        context.attempt.messages + com.embabel.chat.UserMessage(feedback) +
                            com.embabel.chat.AssistantMessage(patch.toString()),
                )
            }

            is PatchApplicationResult.Failure -> {
                LoggerFactory
                    .getLogger(this::class.java)
                    .warn("Patch application failed, falling back: {}", application.reason)
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
internal class LlmPatchRepairStrategy(
    private val promptFactory: BpmnRepairPromptFactory,
    private val patchApplier: BpmnPatchApplier,
) : BpmnRepairStrategy {
    override fun getOrder(): Int = 200

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val patchableDiagnostics =
            context.attempt.evaluation.diagnostics.filter {
                it.repairScope == BpmnRepairScope.OUTLINE || it.repairScope == BpmnRepairScope.PHASE
            }
        if (patchableDiagnostics.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, patchableDiagnostics)
        val patch = context.promptRunner.createObject(feedback, BpmnRepairPatch::class.java)

        return when (val application = patchApplier.apply(context.attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                BpmnRepairResult.Repaired(
                    definition = application.definition,
                    promptText = feedback,
                    messages =
                        context.attempt.messages + com.embabel.chat.UserMessage(feedback) +
                            com.embabel.chat.AssistantMessage(patch.toString()),
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
        val feedback = promptFactory.fullRepairFeedback(context.attempt)
        val repaired = context.promptRunner.createObject(feedback, dev.groknull.bpmner.core.BpmnDefinition::class.java)

        return BpmnRepairResult.Repaired(
            definition = repaired,
            promptText = feedback,
            messages =
                context.attempt.messages + com.embabel.chat.UserMessage(feedback) + com.embabel.chat.AssistantMessage(repaired.toString()),
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
