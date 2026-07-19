/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import dev.groknull.bpmner.bpmn.ClarificationExchange as ApiClarificationExchange

/**
 * Readiness dimensions used by the readiness LLM to score the input prose, and to name
 * missing/underspecified process areas (`ProcessInputAssessment.missingAreas` and related
 * fields reuse this same enum — the dimension-vs-gap distinction is carried by which field
 * holds the value, not by a second, parallel enum type).
 *
 * The `@JsonAlias` entries below absorb the readiness LLM's observed synonym drift: models
 * regularly emit a plausible alternate name (e.g. `"START_STATES"`, `"ACTORS_RESPONSIBILITY"`)
 * for a value here. Without these aliases Jackson rejects the response with
 * `InvalidFormatException`, the LLM call retries, and a more conservative verdict often
 * comes back — silently downgrading READY responses.
 *
 * Aliases are read-only — canonical names still serialise out.
 */
enum class ReadinessDimension {
    PROCESS_BOUNDARY,

    @JsonAlias("START_STATES")
    START_TRIGGER,

    @JsonAlias("END_STATE")
    END_STATES,
    ACTIVITIES,

    @JsonAlias("ACTIVITY_SEQUENCE")
    SEQUENCE_ORDER,

    @JsonAlias("ACTOR_RESPONSIBILITY", "ACTORS_RESPONSIBILITY")
    ACTORS_ROLES,

    @JsonAlias("DECISION_CRITERIA")
    DECISIONS_BRANCHES,

    @JsonAlias("EXCEPTION_HANDLING")
    EXCEPTIONS_REWORK,

    @JsonAlias("INPUT_ARTIFACT", "OUTPUT_ARTIFACT", "INPUTS_ARTIFACTS", "OUTPUTS_ARTIFACTS")
    INPUTS_OUTPUTS_ARTIFACTS,
    SCOPE_CLARITY,

    @JsonAlias("BPMN_PROCESS_SUITABILITY")
    BPMN_SUITABILITY,

    @JsonAlias("SOURCE_TRACE")
    TRACEABILITY_TO_SOURCE,
}

enum class EvidenceSourceType {
    ORIGINAL_INPUT,
    STYLE_GUIDE,
    CLARIFICATION,
    GENERATED_BPMN,
}

@JsonClassDescription("Source evidence supporting a guardrail assessment or trace")
data class SourceEvidence(
    // Optional from the model's perspective: ids are a code/traceability concern, not something the
    // LLM should invent (Mistral omits them; a missing id deserialises as the empty string rather
    // than null). Blank ids are backfilled deterministically by BpmnReadinessAgent.normalize before the
    // assessment flows downstream. Not @NotBlank: the empty default must survive bean validation
    // rather than re-triggering the LLM retry loop.
    @get:JsonPropertyDescription("Stable evidence id (assigned by the system if omitted)")
    val id: String = "",
    @field:NotBlank
    @get:JsonPropertyDescription("Relevant source text excerpt or concise paraphrase")
    val text: String,
    // Not @NotBlank/non-null-with-no-default: nothing in production code reads .sourceType today
    // (it exists for future traceability); the empty default must survive bean validation rather
    // than re-triggering the LLM retry loop for a value nothing downstream requires.
    @get:JsonPropertyDescription("Type of source the evidence came from")
    val sourceType: EvidenceSourceType? = null,
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
    override val questionId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text that was asked")
    override val questionText: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Answer text supplied by the user")
    override val answerText: String,
    @get:JsonPropertyDescription("Missing process areas resolved or affected by this exchange")
    val relatedMissingAreas: List<ReadinessDimension> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions resolved or affected by this exchange")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence or trace metadata attached to this answered exchange")
    val evidence: List<SourceEvidence> = emptyList(),
) : ApiClarificationExchange
