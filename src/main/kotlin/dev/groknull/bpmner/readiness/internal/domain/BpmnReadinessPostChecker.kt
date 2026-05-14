package dev.groknull.bpmner.readiness.internal.domain

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnReadinessConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationQuestion
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.ReadinessDimensionScore
import dev.groknull.bpmner.core.ReadinessVerdict
import org.springframework.stereotype.Component

@Component
internal class BpmnReadinessPostChecker(
    private val bpmnConfig: BpmnConfig = BpmnConfig(),
) {
    private val config: BpmnReadinessConfig
        get() = bpmnConfig.readiness

    fun apply(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): ProcessInputAssessment {
        val text = request.processDescription.lowercase()
        val missingAreas = assessment.missingAreas.toMutableSet()
        val dimensions = assessment.dimensions.associateBy { it.dimension }.toMutableMap()
        var overallScore = assessment.overallScore.coerceIn(MIN_SCORE, MAX_SCORE)

        ReadinessDimension.entries.forEach { dimension ->
            dimensions.putIfAbsent(
                dimension,
                ReadinessDimensionScore(
                    dimension = dimension,
                    score = DEFAULT_DIMENSION_SCORE,
                    rationale = "No model score was provided for ${dimension.name}.",
                ),
            )
        }
        dimensions.replaceAll { _, score -> score.copy(score = score.score.coerceIn(MIN_SCORE, MAX_SCORE)) }

        val processVerbCount = PROCESS_VERBS.count { it.containsMatchIn(text) }
        val hasProcessVerb = processVerbCount > 0
        val hasStartTrigger = START_TRIGGER_MARKERS.any { it.containsMatchIn(text) }
        val hasEndState = END_STATE_MARKERS.any { it.containsMatchIn(text) }
        val hasSequence = SEQUENCE_MARKERS.any { it.containsMatchIn(text) }

        if (!hasStartTrigger) {
            overallScore = minOf(overallScore, CLARIFICATION_CEILING)
            missingAreas += MissingProcessArea.START_TRIGGER
            dimensions.lower(ReadinessDimension.START_TRIGGER, MissingProcessArea.START_TRIGGER)
        }
        if (!hasEndState) {
            overallScore = minOf(overallScore, CLARIFICATION_CEILING)
            missingAreas += MissingProcessArea.END_STATE
            dimensions.lower(ReadinessDimension.END_STATES, MissingProcessArea.END_STATE)
        }
        if (processVerbCount < config.minimumActivityCount) {
            overallScore = minOf(overallScore, CLARIFICATION_CEILING)
            missingAreas += MissingProcessArea.ACTIVITY_SEQUENCE
            dimensions.lower(ReadinessDimension.ACTIVITIES, MissingProcessArea.ACTIVITY_SEQUENCE)
        }
        if (!hasSequence) {
            overallScore = minOf(overallScore, CLARIFICATION_CEILING)
            missingAreas += MissingProcessArea.ACTIVITY_SEQUENCE
            dimensions.lower(ReadinessDimension.SEQUENCE_ORDER, MissingProcessArea.ACTIVITY_SEQUENCE)
        }
        if (!hasProcessVerb) {
            overallScore = minOf(overallScore, NOT_A_PROCESS_CEILING)
            missingAreas += MissingProcessArea.BPMN_PROCESS_SUITABILITY
            dimensions.lower(
                dimension = ReadinessDimension.BPMN_SUITABILITY,
                missingArea = MissingProcessArea.BPMN_PROCESS_SUITABILITY,
                scoreCeiling = NOT_A_PROCESS_CEILING,
            )
        }

        val verdict = verdictFor(overallScore, hasProcessVerb)
        val questions =
            normalizeQuestions(
                questions = assessment.clarificationQuestions,
                missingAreas = missingAreas.toList(),
                verdict = verdict,
            )

        return assessment.copy(
            verdict = verdict,
            overallScore = overallScore,
            dimensions = ReadinessDimension.entries.map { dimensions.getValue(it) },
            missingAreas = missingAreas.toList(),
            clarificationQuestions = questions,
        )
    }

    private fun MutableMap<ReadinessDimension, ReadinessDimensionScore>.lower(
        dimension: ReadinessDimension,
        missingArea: MissingProcessArea,
        scoreCeiling: Int = MISSING_DIMENSION_CEILING,
    ) {
        val current =
            get(dimension)
                ?: ReadinessDimensionScore(
                    dimension = dimension,
                    score = DEFAULT_DIMENSION_SCORE,
                    rationale = "No model score was provided for ${dimension.name}.",
                )
        put(
            dimension,
            current.copy(
                score = minOf(current.score.coerceIn(MIN_SCORE, MAX_SCORE), scoreCeiling),
                missingAreas = (current.missingAreas + missingArea).distinct(),
                rationale = current.rationale.ifBlank { "Deterministic readiness check found missing input." },
            ),
        )
    }

    private fun verdictFor(
        overallScore: Int,
        hasProcessVerb: Boolean,
    ): ReadinessVerdict =
        when {
            !hasProcessVerb -> ReadinessVerdict.NOT_A_PROCESS
            overallScore >= config.readyThreshold -> ReadinessVerdict.READY
            overallScore >= config.clarificationThreshold -> ReadinessVerdict.NEEDS_CLARIFICATION
            else -> ReadinessVerdict.NOT_A_PROCESS
        }

    private fun normalizeQuestions(
        questions: List<ClarificationQuestion>,
        missingAreas: List<MissingProcessArea>,
        verdict: ReadinessVerdict,
    ): List<ClarificationQuestion> {
        val normalizedQuestions =
            questions.mapIndexed { index, question ->
                val areas = question.relatedMissingAreas.ifEmpty { missingAreas.take(1) }
                question.copy(
                    id = question.id.ifBlank { "q${index + 1}" },
                    relatedMissingAreas = areas,
                    relatedDimensions = question.relatedDimensions.ifEmpty { areas.mapNotNull(::dimensionFor) }.distinct(),
                )
            }
        val normalized = normalizedQuestions.toMutableList()

        if (verdict == ReadinessVerdict.NEEDS_CLARIFICATION && normalized.isEmpty()) {
            missingAreas.take(config.maxClarificationQuestions).forEachIndexed { index, area ->
                normalized += questionFor(index + 1, area)
            }
        }

        return normalized
            .filter { it.relatedMissingAreas.isNotEmpty() && it.relatedDimensions.isNotEmpty() }
            .take(config.maxClarificationQuestions)
    }

    private fun questionFor(
        number: Int,
        area: MissingProcessArea,
    ): ClarificationQuestion =
        ClarificationQuestion(
            id = "q$number",
            questionText = questionTextFor(area),
            relatedMissingAreas = listOf(area),
            relatedDimensions = listOfNotNull(dimensionFor(area)),
        )

    private fun questionTextFor(area: MissingProcessArea): String =
        when (area) {
            MissingProcessArea.START_TRIGGER -> "What event or condition starts the process?"
            MissingProcessArea.END_STATE -> "What final state should the process reach?"
            MissingProcessArea.ACTIVITY_SEQUENCE -> "What are the main activities, in order?"
            MissingProcessArea.ACTOR_RESPONSIBILITY -> "Which role is responsible for each activity?"
            MissingProcessArea.DECISION_CRITERIA -> "What decision points and branch criteria are required?"
            MissingProcessArea.EXCEPTION_HANDLING -> "What exceptions or rework paths should be modeled?"
            MissingProcessArea.INPUT_ARTIFACT -> "What inputs does the process consume?"
            MissingProcessArea.OUTPUT_ARTIFACT -> "What outputs does the process produce?"
            MissingProcessArea.PROCESS_BOUNDARY -> "Where does the process begin and end?"
            MissingProcessArea.BPMN_PROCESS_SUITABILITY -> "What repeatable workflow should be modeled?"
            MissingProcessArea.SOURCE_TRACE -> "Which source statement supports this process detail?"
        }

    private fun dimensionFor(area: MissingProcessArea): ReadinessDimension? =
        when (area) {
            MissingProcessArea.PROCESS_BOUNDARY -> ReadinessDimension.PROCESS_BOUNDARY
            MissingProcessArea.START_TRIGGER -> ReadinessDimension.START_TRIGGER
            MissingProcessArea.END_STATE -> ReadinessDimension.END_STATES
            MissingProcessArea.ACTIVITY_SEQUENCE -> ReadinessDimension.SEQUENCE_ORDER
            MissingProcessArea.ACTOR_RESPONSIBILITY -> ReadinessDimension.ACTORS_ROLES
            MissingProcessArea.DECISION_CRITERIA -> ReadinessDimension.DECISIONS_BRANCHES
            MissingProcessArea.EXCEPTION_HANDLING -> ReadinessDimension.EXCEPTIONS_REWORK
            MissingProcessArea.INPUT_ARTIFACT,
            MissingProcessArea.OUTPUT_ARTIFACT,
            -> ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS
            MissingProcessArea.BPMN_PROCESS_SUITABILITY -> ReadinessDimension.BPMN_SUITABILITY
            MissingProcessArea.SOURCE_TRACE -> ReadinessDimension.TRACEABILITY_TO_SOURCE
        }

    private companion object {
        const val MIN_SCORE = 0
        const val MAX_SCORE = 100
        const val DEFAULT_DIMENSION_SCORE = 50
        const val MISSING_DIMENSION_CEILING = 40
        const val CLARIFICATION_CEILING = 74
        const val NOT_A_PROCESS_CEILING = 39

        val PROCESS_VERBS =
            listOf(
                "approve",
                "review",
                "submit",
                "receive",
                "validate",
                "create",
                "update",
                "notify",
                "ship",
                "invoice",
                "pay",
                "reject",
                "escalate",
                "assign",
                "close",
            ).map { Regex("\\b${it}\\w*\\b") }

        val START_TRIGGER_MARKERS =
            listOf("when", "after", "once", "starts", "begins", "receives", "submitted", "requested")
                .map { Regex("\\b$it\\b") }

        val END_STATE_MARKERS =
            listOf("ends", "complete", "completed", "closed", "shipped", "paid", "rejected", "approved", "archived")
                .map { Regex("\\b$it\\b") }

        val SEQUENCE_MARKERS =
            listOf("then", "next", "after", "before", "if", "otherwise", "finally", "once")
                .map { Regex("\\b$it\\b") }
    }
}
