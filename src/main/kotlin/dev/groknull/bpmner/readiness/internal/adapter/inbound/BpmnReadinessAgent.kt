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
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.styleGuideContribution
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.jmolecules.architecture.hexagonal.Application
import org.springframework.context.ApplicationEventPublisher

@Application
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
    )
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val promptRunner = config.readinessAssessor
            .promptRunner(context)
            .withPromptContributor(PromptContributor.fixed(request.styleGuideContribution()))
        val modelAssessment = promptRunner
            .creating(ProcessInputAssessment::class.java)
            .fromTemplate("bpmner/assess_readiness", templateModel(request))
        val assessment = modelAssessment.normalize(thresholds.readyThreshold, thresholds.maxClarificationQuestions)
        eventPublisher.publishEvent(BpmnReadinessAssessedEvent(request, assessment))
        return assessment
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
