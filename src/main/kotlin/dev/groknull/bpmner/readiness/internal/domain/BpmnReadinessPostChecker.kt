/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.domain

import dev.groknull.bpmner.core.BpmnReadinessConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict

internal class BpmnReadinessPostChecker(
    private val config: BpmnReadinessConfig = BpmnReadinessConfig(),
) {
    private val clarificationCeiling: Int
        get() = config.readyThreshold - 1

    fun apply(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): ProcessInputAssessment {
        val text = request.processDescription.lowercase()
        val missingAreas = assessment.missingAreas.toMutableSet()
        val dimensions = assessment.dimensions.associateBy { it.dimension }.toMutableMap()
        var overallScore = assessment.overallScore.coerceIn(MIN_SCORE, MAX_SCORE)

        initializeDimensions(dimensions)

        val distinctProcessVerbCount = PROCESS_VERBS.count { it.containsMatchIn(text) }
        val hasProcessVerb = distinctProcessVerbCount > 0

        overallScore =
            checkMarkers(
                text = text,
                distinctProcessVerbCount = distinctProcessVerbCount,
                hasProcessVerb = hasProcessVerb,
                overallScore = overallScore,
                missingAreas = missingAreas,
                dimensions = dimensions,
            )

        val verdict = verdictFor(overallScore)
        val questions =
            normalizeQuestions(
                questions = assessment.clarificationQuestions,
                missingAreas = missingAreas.toList(),
                verdict = verdict,
                hasProcessVerb = hasProcessVerb,
            )

        return assessment.copy(
            verdict = verdict,
            overallScore = overallScore,
            dimensions = ReadinessDimension.entries.map { dimensions.getValue(it) },
            missingAreas = missingAreas.toList(),
            clarificationQuestions = questions,
        )
    }

    private fun initializeDimensions(dimensions: MutableMap<ReadinessDimension, ReadinessDimensionScore>) {
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
    }

    private fun checkMarkers(
        text: String,
        distinctProcessVerbCount: Int,
        hasProcessVerb: Boolean,
        overallScore: Int,
        missingAreas: MutableSet<MissingProcessArea>,
        dimensions: MutableMap<ReadinessDimension, ReadinessDimensionScore>,
    ): Int {
        var newScore = overallScore
        val hasStartTrigger = START_TRIGGER_MARKERS.any { it.containsMatchIn(text) }
        val hasEndState = END_STATE_MARKERS.any { it.containsMatchIn(text) }
        val hasSequence = SEQUENCE_MARKERS.any { it.containsMatchIn(text) }

        if (!hasStartTrigger) {
            newScore = minOf(newScore, clarificationCeiling)
            missingAreas += MissingProcessArea.START_TRIGGER
            dimensions.lower(ReadinessDimension.START_TRIGGER, MissingProcessArea.START_TRIGGER)
        }
        if (!hasEndState) {
            newScore = minOf(newScore, clarificationCeiling)
            missingAreas += MissingProcessArea.END_STATE
            dimensions.lower(ReadinessDimension.END_STATES, MissingProcessArea.END_STATE)
        }
        if (distinctProcessVerbCount < config.minimumActivityCount) {
            newScore = minOf(newScore, clarificationCeiling)
            missingAreas += MissingProcessArea.ACTIVITY_SEQUENCE
            dimensions.lower(ReadinessDimension.ACTIVITIES, MissingProcessArea.ACTIVITY_SEQUENCE)
        }
        if (!hasSequence) {
            newScore = minOf(newScore, clarificationCeiling)
            missingAreas += MissingProcessArea.ACTIVITY_SEQUENCE
            dimensions.lower(ReadinessDimension.SEQUENCE_ORDER, MissingProcessArea.ACTIVITY_SEQUENCE)
        }
        if (!hasProcessVerb) {
            newScore = minOf(newScore, clarificationCeiling)
            missingAreas += MissingProcessArea.BPMN_PROCESS_SUITABILITY
            dimensions.lower(
                dimension = ReadinessDimension.BPMN_SUITABILITY,
                missingArea = MissingProcessArea.BPMN_PROCESS_SUITABILITY,
            )
        }
        return newScore
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

    private fun verdictFor(overallScore: Int): ReadinessVerdict =
        if (overallScore >= config.readyThreshold) {
            ReadinessVerdict.READY
        } else {
            ReadinessVerdict.NEEDS_CLARIFICATION
        }

    private fun normalizeQuestions(
        questions: List<ClarificationQuestion>,
        missingAreas: List<MissingProcessArea>,
        verdict: ReadinessVerdict,
        hasProcessVerb: Boolean,
    ): List<ClarificationQuestion> {
        if (verdict == ReadinessVerdict.NEEDS_CLARIFICATION && !hasProcessVerb) {
            return listOf(guidingWorkflowQuestion())
        }

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

    private fun guidingWorkflowQuestion(): ClarificationQuestion =
        ClarificationQuestion(
            id = "q1",
            questionText = "Could you describe the workflow as a sequence of steps with a clear start and end?",
            relatedMissingAreas = listOf(MissingProcessArea.BPMN_PROCESS_SUITABILITY),
            relatedDimensions = listOf(ReadinessDimension.BPMN_SUITABILITY),
        )

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

        // Domain-broad heuristic fallback covering business, automated, technical, scientific, and
        // personal workflows. The model assessment remains the primary signal; these markers are the
        // floor the deterministic post-check uses to detect workflow signal.
        val PROCESS_VERBS =
            listOf(
                // Business / operational
                "approve",
                "review",
                "submit",
                "send",
                "receive",
                "deliver",
                "publish",
                "execute",
                "process",
                "request",
                "respond",
                "generate",
                "validate",
                "create",
                "update",
                "notify",
                "complete",
                "ship",
                "invoice",
                "pay",
                "reject",
                "escalate",
                "assign",
                "close",
                // Technical / automated / pipeline
                "build",
                "compile",
                "compose",
                "deploy",
                "dispatch",
                "emit",
                "extract",
                "fetch",
                "finalize",
                "forward",
                "import",
                "ingest",
                "merge",
                "parse",
                "produce",
                "refine",
                "render",
                "repair",
                "retrieve",
                "route",
                "store",
                "transform",
                "transmit",
                "verify",
            ).map { Regex("\\b${it}\\w*\\b") }

        val START_TRIGGER_MARKERS =
            listOf(
                "when",
                "after",
                "once",
                "starts",
                "begins",
                "receives",
                "submitted",
                "requested",
                "triggered",
                "on",
                "upon",
                "fires",
                "arrives",
                "invoked",
                "called",
            ).map { Regex("\\b$it\\b") }

        val END_STATE_MARKERS =
            listOf(
                "ends",
                "complete",
                "completed",
                "closed",
                "shipped",
                "paid",
                "rejected",
                "approved",
                "archived",
                "returned",
                "emitted",
                "produced",
                "finalized",
                "generated",
                "delivered",
                "done",
                "terminates",
                "terminated",
            ).map { Regex("\\b$it\\b") }

        val SEQUENCE_MARKERS =
            listOf(
                "then",
                "next",
                "after",
                "before",
                "if",
                "otherwise",
                "finally",
                "once",
                "subsequently",
                "meanwhile",
                "until",
                "whereupon",
                "following",
            ).map { Regex("\\b$it\\b") }
    }
}
