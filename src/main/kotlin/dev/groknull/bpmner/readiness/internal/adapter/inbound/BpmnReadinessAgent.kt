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
import dev.groknull.bpmner.bpmn.styleGuideContribution
import dev.groknull.bpmner.llm.publishOnInvalidLlmReturn
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.BpmnReadinessAssessmentException
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.context.ApplicationEventPublisher

@InfrastructureRing
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnReadinessConfig,
    private val thresholds: BpmnReadinessThresholdsConfig,
    private val eventPublisher: ApplicationEventPublisher,
) {

    @AchievesGoal(
        description = "Assess raw BPMN generation input for process readiness",
        export = Export(name = "assessReadiness", startingInputTypes = [BpmnRequest::class]),
    )
    @Action(
        description = "Assess raw BPMN generation input for process readiness",
        // The single readiness producer must advertise every gate condition it can establish, so the
        // shell path can plan from a bare BpmnRequest to approval, clarification, or a blocked result.
        post = ["assessmentReady", "clarificationAvailable", "clarificationBlocked"],
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val modelAssessment = requestAssessment(request, context)
        val assessment = modelAssessment.normalize(thresholds.readyThreshold, thresholds.maxClarificationQuestions)
        eventPublisher.publishEvent(BpmnReadinessAssessedEvent(request, assessment))
        return assessment
    }

    /**
     * Translates the framework's typed exceptions at this seam so the failure type stays legible —
     * [BpmnReadinessAssessmentException] means "the readiness model failed to produce a structured
     * response," not "the model assessed the input and found it lacking" (that is the legitimate
     * `NEEDS_CLARIFICATION` verdict, which is not an exception). Extracted from [assessReadiness] so
     * detekt's `ThrowsCount` discipline holds at the action method.
     */
    private fun requestAssessment(request: BpmnRequest, context: OperationContext): ProcessInputAssessment {
        val promptRunner = config.readinessAssessor
            .promptRunner(context)
            .withPromptContributor(PromptContributor.fixed(request.styleGuideContribution()))
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
        "processDescription" to request.processDescription,
        "clarificationHistory" to request.clarificationHistory.map {
            mapOf(
                "questionId" to it.questionId,
                "questionText" to it.questionText,
                "answerText" to it.answerText,
            )
        },
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
        if (item.id.isBlank()) item.copy(id = "q${index + 1}") else item
    }.take(maxClarificationQuestions)

    return this.copy(
        verdict = verdictNormalized,
        overallScore = overallScoreNormalized,
        dimensions = normalizedDimensions,
        evidence = normalizedEvidence,
        clarificationQuestions = normalizedQuestions,
    )
}
