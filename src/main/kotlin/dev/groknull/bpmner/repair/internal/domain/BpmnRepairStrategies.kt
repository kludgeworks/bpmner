/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.Actor
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.RepairKind
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class TargetedLabelRepairStrategy(
    private val config: BpmnConfig,
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(TargetedLabelRepairStrategy::class.java)

    override fun getOrder(): Int = 150

    @Suppress("TooGenericExceptionCaught")
    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) && d.repairScope == BpmnRepairScope.LABEL
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)
        val runner = context.promptRunner(config.labelRepairer, promptFactory)
        val patch =
            try {
                runner.createObject(
                    context.attempt.messages + UserMessage(feedback),
                    BpmnRepairPatch::class.java,
                )
            } catch (e: RuntimeException) {
                logger.warn("LLM label patch creation failed: {}", e.message)
                return BpmnRepairResult.NotApplicable
            }

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
                logger.warn("LLM label patch application failed, falling back: {}", application.reason)
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
    private val config: BpmnConfig,
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(LlmPatchRepairStrategy::class.java)

    override fun getOrder(): Int = 200

    @Suppress("TooGenericExceptionCaught")
    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) &&
                    (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)
        val runner = context.promptRunner(config.patchRepairer, promptFactory)
        val patch =
            try {
                runner.createObject(
                    context.attempt.messages + UserMessage(feedback),
                    BpmnRepairPatch::class.java,
                )
            } catch (e: RuntimeException) {
                logger.warn("LLM patch creation failed: {}", e.message)
                return BpmnRepairResult.NotApplicable
            }

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
                logger.warn("LLM patch application failed, falling back: {}", application.reason)
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
    private val config: BpmnConfig,
    private val promptFactory: BpmnRepairPromptPort,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(FullLlmRewriteRepairStrategy::class.java)

    override fun getOrder(): Int = 300

    @Suppress("TooGenericExceptionCaught")
    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.fullRepairFeedback(context.attempt, candidates, context.localOutcome)
        val runner = context.promptRunner(config.rewriteRepairer, promptFactory)
        val repaired =
            try {
                runner.createObject(
                    context.attempt.messages + UserMessage(feedback),
                    BpmnDefinition::class.java,
                )
            } catch (e: RuntimeException) {
                logger.warn("LLM rewrite failed: {}", e.message)
                return BpmnRepairResult.NotApplicable
            }

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

private fun BpmnRepairStrategyContext.promptRunner(
    actor: Actor<Persona>,
    promptFactory: BpmnRepairPromptPort,
): PromptRunner {
    val runner =
        actor
            .promptRunner(operationContext)
            .withPromptContributor(request)
    val docsPrompt = promptFactory.lintRuleDocsPrompt(attempt.diagnostics)
    return if (docsPrompt != null) runner.withPromptContributor(docsPrompt) else runner
}
