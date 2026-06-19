/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.readiness.MissingProcessArea
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.SourceEvidence
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

enum class ReadinessVerdict {
    READY,
    NEEDS_CLARIFICATION,
}

@JsonClassDescription("Readiness assessment of whether source input is sufficient to generate BPMN")
data class ProcessInputAssessment(
    @get:JsonPropertyDescription("Readiness verdict for the supplied process input")
    val verdict: ReadinessVerdict,
    @field:Min(0)
    @get:JsonPropertyDescription("Overall readiness score, where higher means more generation-ready")
    val overallScore: Int,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Dimension-level readiness scores supporting the verdict")
    val dimensions: List<ReadinessDimensionScore>,
    @get:JsonPropertyDescription("Process areas that are missing or underspecified")
    val missingAreas: List<MissingProcessArea> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Clarification questions proposed for unresolved missing areas")
    val clarificationQuestions: List<ClarificationQuestion> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Source evidence used to justify the assessment")
    val evidence: List<SourceEvidence> = emptyList(),
    @field:NotBlank
    @get:JsonPropertyDescription("Short explanation for the readiness verdict")
    val rationale: String,
)

@JsonClassDescription("Score for a single readiness dimension")
data class ReadinessDimensionScore(
    @get:JsonPropertyDescription("Readiness dimension being scored")
    val dimension: ReadinessDimension,
    @field:Min(0)
    @get:JsonPropertyDescription("Score for this dimension, where higher means more complete")
    val score: Int,
    @field:NotBlank
    @get:JsonPropertyDescription("Explanation of the score")
    val rationale: String,
    @get:JsonPropertyDescription("Missing process areas related to this dimension")
    val missingAreas: List<MissingProcessArea> = emptyList(),
)

@JsonClassDescription("Clarification question proposed by the readiness model")
data class ClarificationQuestion(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable question id for correlating later answers")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text to ask the user")
    val questionText: String,
    @get:JsonPropertyDescription("Missing process areas this question is intended to resolve")
    val relatedMissingAreas: List<MissingProcessArea> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions this question is intended to improve")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Size(max = 8)
    @get:JsonPropertyDescription("Optional bounded answer options when the question is multiple choice")
    val options: List<String> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence motivating the question")
    val evidence: List<SourceEvidence> = emptyList(),
)
