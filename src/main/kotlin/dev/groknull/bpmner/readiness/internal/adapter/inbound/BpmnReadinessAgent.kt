/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.promptContributions
import dev.groknull.bpmner.llm.publishOnInvalidLlmReturn
import dev.groknull.bpmner.readiness.BpmnReadinessAssessmentException
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.ObjectMapper

@InfrastructureRing
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnReadinessConfig,
    private val thresholds: BpmnReadinessThresholdsConfig,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Advertises every gate condition it can establish (`assessmentReady`,
     * `clarificationAvailable`, `clarificationBlocked`), so the shell path can plan from a bare
     * [BpmnRequest] to approval, clarification, or a blocked result.
     */
    @AchievesGoal(
        description = "Assess raw BPMN generation input for process readiness",
        export = Export(name = "assessReadiness", startingInputTypes = [BpmnRequest::class]),
    )
    @Action(
        description = "Assess raw BPMN generation input for process readiness",
        post = ["assessmentReady", "clarificationAvailable", "clarificationBlocked"],
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val modelAssessment = requestAssessment(request, context)
        // No BpmnReadinessAssessedEvent published here: this action runs inside its own scoped,
        // ephemeral Embabel sub-process (AgentPlatformBpmnReadinessInvoker), so
        // AgentProcess.get() here resolves to that child process, not the outer web-facing run
        // the browser is subscribed to. The orchestrator — which starts and awaits this
        // sub-process synchronously — is the only call site with the correct process id in
        // scope, and publishes the event itself (BpmnGenerationAgent.assessReadiness/reassess).
        return modelAssessment.normalize(thresholds.readyThreshold, thresholds.maxClarificationQuestions)
    }

    /**
     * Translates the framework's typed exceptions so the failure type stays legible —
     * [BpmnReadinessAssessmentException] means "the readiness model failed to produce a structured
     * response," not "the model assessed the input and found it lacking" (that is the legitimate
     * `NEEDS_CLARIFICATION` verdict, which is not an exception).
     */
    private fun requestAssessment(request: BpmnRequest, context: OperationContext): ProcessInputAssessment {
        val promptRunner = config.readinessAssessor
            .promptRunner(context)
            .withPromptContributor(PromptContributor.fixed(request.promptContributions()))
        return try {
            eventPublisher.publishOnInvalidLlmReturn("readiness") {
                promptRunner
                    .creating(ProcessInputAssessment::class.java)
                    .fromTemplate("bpmner/assess_readiness", templateModel(request))
            }
        } catch (e: InvalidLlmReturnFormatException) {
            throw BpmnReadinessAssessmentException(
                "Readiness model failed to produce a structured assessment: ${e.message}",
                e,
            )
        } catch (e: InvalidLlmReturnTypeException) {
            throw BpmnReadinessAssessmentException(
                "Readiness model returned an invalid ProcessInputAssessment: ${e.message}",
                e,
            )
        }
    }

    private fun templateModel(request: BpmnRequest): Map<String, Any> = mapOf(
        "readyThreshold" to thresholds.readyThreshold,
        "maxClarificationQuestions" to thresholds.maxClarificationQuestions,
        "processDescriptionJson" to objectMapper.writeValueAsString(request.processDescription),
        "hasClarificationHistory" to request.clarificationHistory.isNotEmpty(),
        "clarificationHistoryJson" to objectMapper.writeValueAsString(
            request.clarificationHistory.map {
                mapOf(
                    "questionId" to it.questionId,
                    "questionText" to it.questionText,
                    "answerText" to it.answerText,
                )
            },
        ),
    )
}

private const val MIN_SCORE = 0
private const val MAX_SCORE = 100
private const val DEFAULT_DIMENSION_SCORE = 50

internal fun ProcessInputAssessment.normalize(readyThreshold: Int, maxClarificationQuestions: Int): ProcessInputAssessment {
    val overallScoreNormalized = overallScore.coerceIn(MIN_SCORE, MAX_SCORE)
    val verdictNormalized = if (overallScoreNormalized >= readyThreshold) {
        ReadinessVerdict.READY
    } else {
        ReadinessVerdict.NEEDS_CLARIFICATION
    }

    val dimensionsMap = dimensions.associateBy { it.dimension }.toMutableMap()
    ReadinessDimension.entries.forEach { dimension ->
        dimensionsMap.putIfAbsent(
            dimension,
            ReadinessDimensionScore(
                dimension = dimension,
                score = DEFAULT_DIMENSION_SCORE,
                rationale = "No model score was provided for ${dimension.name}.",
            ),
        )
    }
    dimensionsMap.replaceAll { _, score ->
        score.copy(score = score.score.coerceIn(MIN_SCORE, MAX_SCORE))
    }
    val normalizedDimensions = ReadinessDimension.entries.map { dimensionsMap.getValue(it) }

    val normalizedEvidence = evidence.mapIndexed { index, item ->
        if (item.id.isBlank()) item.copy(id = "ev-${index + 1}") else item
    }

    val normalizedQuestions = clarificationQuestions.mapIndexed { index, item ->
        val options = item.options.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CLARIFICATION_OPTIONS)
            .takeIf { it.size >= MIN_CLARIFICATION_OPTIONS }
            .orEmpty()
        item.copy(
            id = item.id.ifBlank { "q${index + 1}" },
            options = options,
        )
    }.take(maxClarificationQuestions)

    return this.copy(
        verdict = verdictNormalized,
        overallScore = overallScoreNormalized,
        dimensions = normalizedDimensions,
        evidence = normalizedEvidence,
        clarificationQuestions = normalizedQuestions,
    )
}

private const val MIN_CLARIFICATION_OPTIONS = 2
private const val MAX_CLARIFICATION_OPTIONS = 4
