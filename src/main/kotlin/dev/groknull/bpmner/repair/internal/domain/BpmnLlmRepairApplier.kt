/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.Actor
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.styleGuideContribution
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.llm.publishOnInvalidLlmReturn
import dev.groknull.bpmner.repair.BpmnRepairConfig
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnDefinition
import dev.groknull.bpmner.repair.internal.adapter.toSealed
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@Component
internal class BpmnLlmRepairApplier(
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
    private val advancer: BpmnRepairAdvancer,
    private val config: BpmnRepairConfig,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnLlmRepairApplier::class.java)

    fun applyLlmLabelPatch(
        repairEval: BpmnRepairEvaluation,
        context: ActionContext,
        candidates: List<BpmnDiagnostic>,
    ): BpmnRepairEvaluation {
        val feedback = promptFactory.patchFeedback(repairEval.definition, candidates)
        return applyLlmPatch(
            repairEval = repairEval,
            operationContext = context,
            actor = config.labelRepairer,
            feedback = feedback,
            patchTypeName = "LLM label patch",
            labelOnly = true,
            role = "repair-label",
        )
    }

    fun applyLlmStructuralPatch(
        repairEval: BpmnRepairEvaluation,
        context: ActionContext,
        candidates: List<BpmnDiagnostic>,
    ): BpmnRepairEvaluation {
        val feedback = promptFactory.patchFeedback(repairEval.definition, candidates)
        return applyLlmPatch(
            repairEval = repairEval,
            operationContext = context,
            actor = config.patchRepairer,
            feedback = feedback,
            patchTypeName = "LLM structural patch",
            labelOnly = false,
            role = "repair-patch",
        )
    }

    fun applyFullLlmRewrite(
        repairEval: BpmnRepairEvaluation,
        context: ActionContext,
        candidates: List<BpmnDiagnostic>,
    ): BpmnRepairEvaluation {
        val feedback = promptFactory.fullRepairFeedback(repairEval.toAttempt(), candidates)
        val runner = promptRunner(repairEval, context, config.rewriteRepairer)
        // `createObject` (not `createObjectIfPossible`) routes through `LlmOperations.createObject`,
        // which is what `EmbabelMockitoIntegrationTest.whenCreateObject` mocks.
        val repaired: BpmnDefinition = requestLlmDefinition(runner, repairEval.messages + UserMessage(feedback))
        return advancer.revalidateAndAdvance(
            prior = repairEval,
            repaired = repaired,
            appendedMessages = listOf(UserMessage(feedback), AssistantMessage(repaired.toString())),
            promptText = feedback,
        )
    }

    @Suppress("LongParameterList")
    private fun applyLlmPatch(
        repairEval: BpmnRepairEvaluation,
        operationContext: OperationContext,
        actor: Actor<Persona>,
        feedback: String,
        patchTypeName: String,
        labelOnly: Boolean,
        role: String,
    ): BpmnRepairEvaluation {
        val runner = promptRunner(repairEval, operationContext, actor)
        // See applyFullLlmRewrite for why this uses `createObject` rather than
        // `createObjectIfPossible` — alignment with what `whenCreateObject` mocks.
        val patch: BpmnRepairPatch = requestLlmPatch(
            runner = runner,
            messages = repairEval.messages + UserMessage(feedback),
            patchTypeName = patchTypeName,
            labelOnly = labelOnly,
            role = role,
        )
        val application = patchApplier.apply(repairEval.definition, patch)
        val success = patchSuccessOrReplan(application, patchTypeName)
        return advancer.revalidateAndAdvance(
            prior = repairEval,
            repaired = success.definition,
            appendedMessages = listOf(UserMessage(feedback), AssistantMessage(patch.toString())),
            promptText = feedback,
        )
    }

    private fun promptRunner(
        repairEval: BpmnRepairEvaluation,
        operationContext: OperationContext,
        actor: Actor<Persona>,
    ): PromptRunner {
        val styleContribution = PromptContributor.fixed(repairEval.request.styleGuideContribution())
        val baseRunner = actor.promptRunner(operationContext).withPromptContributor(styleContribution)
        val docsPrompt = promptFactory.lintRuleDocsPrompt(repairEval.diagnostics)
        return if (docsPrompt != null) baseRunner.withPromptContributor(docsPrompt) else baseRunner
    }

    private fun requestLlmDefinition(
        runner: PromptRunner,
        messages: List<com.embabel.chat.Message>,
    ): BpmnDefinition = try {
        eventPublisher.publishOnInvalidLlmReturn("repair-rewrite") {
            runner.createObject(messages, FlatBpmnDefinition::class.java)
        }.toSealed()
    } catch (e: InvalidLlmReturnFormatException) {
        throw RepairReplans.signal("LLM rewrite failed to produce a structured definition: ${e.message}", e)
    } catch (e: InvalidLlmReturnTypeException) {
        throw RepairReplans.signal("LLM rewrite returned a definition that failed validation: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        // FlatBpmnDefinition.toSealed() throws when the LLM emits a structurally
        // incomplete node (e.g. BUSINESS_RULE_TASK with no decisionRef). Surface it as
        // a replan signal so the planner retries instead of aborting the repair loop.
        throw RepairReplans.signal("LLM rewrite produced a structurally incomplete definition: ${e.message}", e)
    }

    private fun requestLlmPatch(
        runner: PromptRunner,
        messages: List<com.embabel.chat.Message>,
        patchTypeName: String,
        labelOnly: Boolean,
        role: String,
    ): BpmnRepairPatch = try {
        eventPublisher.publishOnInvalidLlmReturn(role) {
            if (labelOnly) {
                runner
                    .creating(BpmnRepairPatch::class.java)
                    .withoutProperties("node", "edge")
                    .fromMessages(messages)
            } else {
                runner.createObject(messages, BpmnRepairPatch::class.java)
            }
        }
    } catch (e: InvalidLlmReturnFormatException) {
        throw RepairReplans.signal("$patchTypeName failed to produce a structured patch: ${e.message}", e)
    } catch (e: InvalidLlmReturnTypeException) {
        throw RepairReplans.signal("$patchTypeName returned a patch that failed validation: ${e.message}", e)
    }

    /**
     * Unwraps a [PatchApplicationResult] into its [PatchApplicationResult.Success] form or
     * throws `ReplanRequestedException`.
     */
    private fun patchSuccessOrReplan(
        application: PatchApplicationResult,
        patchTypeName: String,
    ): PatchApplicationResult.Success {
        val reason = when (application) {
            is PatchApplicationResult.Success -> return application

            is PatchApplicationResult.Failure -> {
                logger.warn("{} application failed: {}", patchTypeName, application.reason)
                "$patchTypeName application failed: ${application.reason}"
            }

            PatchApplicationResult.NoOp -> "$patchTypeName produced no-op"
        }
        throw RepairReplans.signal(reason)
    }
}
