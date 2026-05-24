/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import dev.groknull.bpmner.api.ClarificationExchange as ApiClarificationExchange

/**
 * Readiness dimensions used by the readiness LLM to score the input prose.
 *
 * The `@JsonAlias` entries below absorb the parallel naming used by [MissingProcessArea]:
 * the readiness LLM regularly emits a [MissingProcessArea]-style name (e.g.
 * `"ACTIVITY_SEQUENCE"`) where a [ReadinessDimension] is expected (typically inside
 * `ClarificationQuestion.relatedDimensions`). Without these aliases Jackson rejects the
 * response with `InvalidFormatException`, the LLM call retries, and a more conservative
 * verdict often comes back — silently downgrading READY responses. The canonical
 * cross-mapping is documented in
 * `dev.groknull.bpmner.readiness.internal.domain.BpmnReadinessPostChecker.dimensionFor`.
 *
 * Aliases are read-only — canonical names still serialise out.
 */
enum class ReadinessDimension {
    PROCESS_BOUNDARY,
    START_TRIGGER,

    @JsonAlias("END_STATE")
    END_STATES,
    ACTIVITIES,

    @JsonAlias("ACTIVITY_SEQUENCE")
    SEQUENCE_ORDER,

    @JsonAlias("ACTOR_RESPONSIBILITY")
    ACTORS_ROLES,

    @JsonAlias("DECISION_CRITERIA")
    DECISIONS_BRANCHES,

    @JsonAlias("EXCEPTION_HANDLING")
    EXCEPTIONS_REWORK,

    @JsonAlias("INPUT_ARTIFACT", "OUTPUT_ARTIFACT")
    INPUTS_OUTPUTS_ARTIFACTS,
    SCOPE_CLARITY,

    @JsonAlias("BPMN_PROCESS_SUITABILITY")
    BPMN_SUITABILITY,

    @JsonAlias("SOURCE_TRACE")
    TRACEABILITY_TO_SOURCE,
}

/**
 * Missing process areas surfaced by the readiness assessor. Mirror of [ReadinessDimension]
 * with finer-grained gap categories. The `@JsonAlias` entries below absorb the parallel
 * naming used by [ReadinessDimension] so an LLM that emits a [ReadinessDimension] name
 * where a [MissingProcessArea] is expected still deserialises cleanly. Aliases are
 * read-only — canonical names still serialise out. See [ReadinessDimension] for the
 * full rationale.
 */
enum class MissingProcessArea {
    PROCESS_BOUNDARY,
    START_TRIGGER,

    @JsonAlias("END_STATES")
    END_STATE,

    // ACTIVITIES (a ReadinessDimension) maps onto ACTIVITY_SEQUENCE per the post-checker
    // (BpmnReadinessPostChecker.dimensionFor returns ACTIVITIES for ACTIVITY_SEQUENCE).
    // SEQUENCE_ORDER is the dimension that pairs with this area, so it's the natural alias.
    @JsonAlias("SEQUENCE_ORDER", "ACTIVITIES")
    ACTIVITY_SEQUENCE,

    @JsonAlias("ACTORS_ROLES")
    ACTOR_RESPONSIBILITY,

    @JsonAlias("DECISIONS_BRANCHES")
    DECISION_CRITERIA,

    @JsonAlias("EXCEPTIONS_REWORK")
    EXCEPTION_HANDLING,

    // No alias on INPUT_ARTIFACT: INPUTS_OUTPUTS_ARTIFACTS could legitimately mean either
    // input or output, so we attach it to OUTPUT_ARTIFACT (the more common gap in practice).
    INPUT_ARTIFACT,

    @JsonAlias("INPUTS_OUTPUTS_ARTIFACTS")
    OUTPUT_ARTIFACT,

    @JsonAlias("BPMN_SUITABILITY")
    BPMN_PROCESS_SUITABILITY,

    @JsonAlias("TRACEABILITY_TO_SOURCE")
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
    override val questionId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text that was asked")
    override val questionText: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Answer text supplied by the user")
    override val answerText: String,
    @get:JsonPropertyDescription("Missing process areas resolved or affected by this exchange")
    val relatedMissingAreas: List<MissingProcessArea> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions resolved or affected by this exchange")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence or trace metadata attached to this answered exchange")
    val evidence: List<SourceEvidence> = emptyList(),
) : ApiClarificationExchange
