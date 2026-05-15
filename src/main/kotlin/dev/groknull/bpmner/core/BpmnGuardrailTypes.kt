package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero

enum class ReadinessDimension {
    PROCESS_BOUNDARY,
    START_TRIGGER,
    END_STATES,
    ACTIVITIES,
    SEQUENCE_ORDER,
    ACTORS_ROLES,
    DECISIONS_BRANCHES,
    EXCEPTIONS_REWORK,
    INPUTS_OUTPUTS_ARTIFACTS,
    SCOPE_CLARITY,
    BPMN_SUITABILITY,
    TRACEABILITY_TO_SOURCE,
}

enum class MissingProcessArea {
    PROCESS_BOUNDARY,
    START_TRIGGER,
    END_STATE,
    ACTIVITY_SEQUENCE,
    ACTOR_RESPONSIBILITY,
    DECISION_CRITERIA,
    EXCEPTION_HANDLING,
    INPUT_ARTIFACT,
    OUTPUT_ARTIFACT,
    BPMN_PROCESS_SUITABILITY,
    SOURCE_TRACE,
}

enum class AlignmentClassification {
    SUPPORTED,
    ASSUMED,
    UNSUPPORTED,
    COVERED,
    PARTIALLY_COVERED,
    MISSING,
    EXTRA,
    CONTRADICTED,
}

enum class EvidenceSourceType {
    ORIGINAL_INPUT,
    STYLE_GUIDE,
    CLARIFICATION,
    GENERATED_BPMN,
}

@JsonClassDescription("Source evidence supporting a guardrail assessment or trace")
data class SourceEvidence(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable evidence id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Relevant source text excerpt or concise paraphrase")
    val text: String,
    @get:JsonPropertyDescription("Type of source the evidence came from")
    val sourceType: EvidenceSourceType,
    @get:JsonPropertyDescription("Optional source reference, such as a filename or clarification question id")
    val sourceRef: String? = null,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Optional inclusive start offset in the source text")
    val startOffset: Int? = null,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Optional exclusive end offset in the source text")
    val endOffset: Int? = null,
)

@JsonClassDescription("Answered clarification history item")
data class ClarificationExchange(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable id of the clarification question that was answered")
    val questionId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text that was asked")
    val questionText: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Answer text supplied by the user")
    val answerText: String,
    @get:JsonPropertyDescription("Missing process areas resolved or affected by this exchange")
    val relatedMissingAreas: List<MissingProcessArea> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions resolved or affected by this exchange")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence or trace metadata attached to this answered exchange")
    val evidence: List<SourceEvidence> = emptyList(),
)
