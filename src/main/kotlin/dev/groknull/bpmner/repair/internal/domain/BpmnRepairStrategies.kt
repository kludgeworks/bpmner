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
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRepairScope
import org.jmolecules.ddd.annotation.Service
import org.slf4j.Logger
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

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) && d.repairScope == BpmnRepairScope.LABEL
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)

        return repairWithPatch(
            context = context,
            actor = config.labelRepairer,
            promptFactory = promptFactory,
            patchApplier = patchApplier,
            feedback = feedback,
            patchTypeName = "LLM label patch",
            logger = logger,
        )
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

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome) &&
                    (d.repairScope == BpmnRepairScope.OUTLINE || d.repairScope == BpmnRepairScope.PHASE)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.patchFeedback(context.attempt.definition, candidates, context.localOutcome)

        return repairWithPatch(
            context = context,
            actor = config.patchRepairer,
            promptFactory = promptFactory,
            patchApplier = patchApplier,
            feedback = feedback,
            patchTypeName = "LLM patch",
            logger = logger,
        )
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

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val candidates =
            context.attempt.evaluation.diagnostics.filter { d ->
                eligibleForLlm(d, context.localOutcome)
            }
        if (candidates.isEmpty()) return BpmnRepairResult.NotApplicable

        val feedback = promptFactory.fullRepairFeedback(context.attempt, candidates, context.localOutcome)
        val runner = context.promptRunner(config.rewriteRepairer, promptFactory)
        val repaired =
            runner.createObjectIfPossible(
                context.attempt.messages + UserMessage(feedback),
                BpmnDefinition::class.java,
            )
        if (repaired == null) {
            logger.warn("LLM rewrite returned no structured definition, falling back")
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

// Refactoring duplicate blocks across two strategies leaves the helper with the same workflow inputs.
@Suppress("LongParameterList")
private fun repairWithPatch(
    context: BpmnRepairStrategyContext,
    actor: Actor<Persona>,
    promptFactory: BpmnRepairPromptPort,
    patchApplier: BpmnPatchApplicationPort,
    feedback: String,
    patchTypeName: String,
    logger: Logger,
): BpmnRepairResult {
    val runner = context.promptRunner(actor, promptFactory)
    val patch =
        runner.createObjectIfPossible(
            context.attempt.messages + UserMessage(feedback),
            BpmnRepairPatch::class.java,
        )
    if (patch == null) {
        logger.warn("$patchTypeName creation returned no structured patch, falling back")
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
            logger.warn("$patchTypeName application failed, falling back: {}", application.reason)
            BpmnRepairResult.NotApplicable
        }

        PatchApplicationResult.NoOp -> {
            BpmnRepairResult.NotApplicable
        }
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
