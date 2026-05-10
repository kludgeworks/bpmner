package dev.groknull.bpmner.repair.internal

import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnFingerprintService
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(10)
internal class DeterministicTopologyRepairStrategy(
    private val topologyRepair: BpmnTopologyRepair,
    private val bpmnPatchApplier: BpmnPatchApplier,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(DeterministicTopologyRepairStrategy::class.java)

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        val patch = topologyRepair.buildTopologyPatch(attempt.definition, attempt.diagnostics)
            ?: return BpmnRepairResult.NotApplicable
        val promptText = "deterministic-topology-repair:${patch.reason}"
        return when (val patchResult = bpmnPatchApplier.apply(attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                logger.info(
                    "Topology repair applied deterministically: ops={}, reason={}",
                    patch.operations.size,
                    patch.reason ?: "-",
                )
                BpmnRepairResult.Repaired(
                    definition = patchResult.definition,
                    messages = attempt.messages,
                    promptText = promptText,
                )
            }
            is PatchApplicationResult.NoOp -> BpmnRepairResult.TerminalFailure(
                "deterministic topology patch was a no-op on attempt ${attempt.repairAttempts + 1}"
            )
            is PatchApplicationResult.Failure -> {
                logger.warn(
                    "Deterministic topology repair failed ({}), falling through to LLM strategies",
                    patchResult.reason,
                )
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

@Component
@Order(20)
internal class TargetedLabelRepairStrategy(
    private val bpmnPatchApplier: BpmnPatchApplier,
    private val promptFactory: BpmnRepairPromptFactory,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(TargetedLabelRepairStrategy::class.java)

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        if (!attempt.diagnostics.isLabelStyleOnly()) {
            return BpmnRepairResult.NotApplicable
        }
        val feedback = promptFactory.targetedLabelPatchFeedback(attempt.definition, attempt.diagnostics)
        val patch = context.promptRunner.createObject(
            messages = attempt.messages + UserMessage(feedback),
            outputClass = BpmnRepairPatch::class.java,
        )
        return when (val patchResult = bpmnPatchApplier.apply(attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                logger.info("Targeted label repair applied: ops={}, reason={}", patch.operations.size, patch.reason ?: "-")
                BpmnRepairResult.Repaired(
                    definition = patchResult.definition,
                    messages = attempt.messages + UserMessage(feedback),
                    promptText = feedback,
                )
            }
            is PatchApplicationResult.NoOp -> BpmnRepairResult.TerminalFailure(
                "patch was a no-op on attempt ${attempt.repairAttempts + 1}"
            )
            is PatchApplicationResult.Failure -> {
                logger.warn("Targeted label patch failed ({}), falling through to full correction", patchResult.reason)
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

@Component
@Order(30)
internal class LlmPatchRepairStrategy(
    private val bpmnPatchApplier: BpmnPatchApplier,
    private val promptFactory: BpmnRepairPromptFactory,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(LlmPatchRepairStrategy::class.java)

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        if (!attempt.diagnostics.isPatchable()) {
            return BpmnRepairResult.NotApplicable
        }
        val feedback = promptFactory.patchFeedback(attempt.definition, attempt.diagnostics)
        val patch = context.promptRunner.createObject(
            messages = attempt.messages + UserMessage(feedback),
            outputClass = BpmnRepairPatch::class.java,
        )
        return when (val patchResult = bpmnPatchApplier.apply(attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                logger.info("Patch repair applied: ops={}, reason={}", patch.operations.size, patch.reason ?: "-")
                BpmnRepairResult.Repaired(
                    definition = patchResult.definition,
                    messages = attempt.messages + UserMessage(feedback),
                    promptText = feedback,
                )
            }
            is PatchApplicationResult.NoOp -> BpmnRepairResult.TerminalFailure(
                "patch was a no-op on attempt ${attempt.repairAttempts + 1}"
            )
            is PatchApplicationResult.Failure -> {
                logger.warn("Patch application failed ({}), falling through to full correction", patchResult.reason)
                BpmnRepairResult.NotApplicable
            }
        }
    }
}

@Component
@Order(100)
internal class FullLlmRewriteRepairStrategy(
    private val promptFactory: BpmnRepairPromptFactory,
    private val fingerprints: BpmnFingerprintService,
) : BpmnRepairStrategy {
    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        val feedback = promptFactory.fullRepairFeedback(attempt)
        if (feedback.isBlank()) {
            return BpmnRepairResult.TerminalFailure("empty repair prompt")
        }
        val withFeedback = attempt.messages + UserMessage(feedback)
        val corrected = context.promptRunner.createObject(messages = withFeedback, outputClass = BpmnDefinition::class.java)
        return BpmnRepairResult.Repaired(
            definition = corrected,
            messages = withFeedback + AssistantMessage(fingerprints.serializeDefinition(corrected)),
            promptText = feedback,
        )
    }
}

private fun List<BpmnDiagnostic>.isPatchable(): Boolean {
    if (isEmpty() || size > PATCH_DIAGNOSTIC_LIMIT) return false
    return all { it.isPatchableByLabelFix() }
}

private fun BpmnDiagnostic.isPatchableByLabelFix(): Boolean =
    when (source) {
        BpmnDiagnosticSource.GRAPH -> message.contains("blank", ignoreCase = true)
            || message.contains("name", ignoreCase = true)
        BpmnDiagnosticSource.LINT -> rule != null && PATCHABLE_LINT_RULES.any { rule.contains(it) }
        else -> false
    }

private fun List<BpmnDiagnostic>.isLabelStyleOnly(): Boolean =
    isNotEmpty() && all { it.isLabelStyleRule() }

private fun BpmnDiagnostic.isLabelStyleRule(): Boolean =
    source == BpmnDiagnosticSource.LINT && rule != null &&
        LABEL_STYLE_RULES.any { rule.contains(it) }

private const val PATCH_DIAGNOSTIC_LIMIT = 5
private val PATCHABLE_LINT_RULES = listOf("label", "name", "naming", "act-02", "gtw-01", "gtw-03", "name-02")
private val LABEL_STYLE_RULES = listOf("act-02", "gtw-01", "gtw-03", "name-02")
