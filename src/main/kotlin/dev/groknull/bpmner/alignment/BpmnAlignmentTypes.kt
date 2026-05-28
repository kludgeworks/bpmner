/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.core.AlignmentClassification
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

enum class AlignmentVerdict {
    ALIGNED,
    PARTIALLY_ALIGNED,
    FAILED,
}

@JsonClassDescription("Summary of a generated BPMN definition for alignment checking")
data class BpmnDefinitionSummary(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN process id")
    val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN process name")
    val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Summary of generated BPMN elements")
    val elements: List<BpmnSummaryElement>,
    @field:Valid
    @get:JsonPropertyDescription("Summary of generated BPMN sequence flows")
    val flows: List<BpmnSummaryFlow> = emptyList(),
    @get:JsonPropertyDescription("IDs of elements that are unreachable from start events")
    val unreachableElementIds: List<String> = emptyList(),
)

@JsonClassDescription("Summary of a single generated BPMN element")
data class BpmnSummaryElement(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN element id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN element type")
    val type: String,
    @get:JsonPropertyDescription("Optional BPMN element name")
    val name: String? = null,
)

@JsonClassDescription("Summary of a single generated BPMN sequence flow")
data class BpmnSummaryFlow(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN sequence flow id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source BPMN element id")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target BPMN element id")
    val targetRef: String,
    @get:JsonPropertyDescription("Optional sequence flow name")
    val name: String? = null,
    @get:JsonPropertyDescription("Optional sequence flow condition expression")
    val conditionExpression: String? = null,
)

@JsonClassDescription(
    "Alignment findings. List ONLY misalignments in `issues` — empty list means the BPMN " +
        "is fully aligned with the contract. Provide a 1-2 sentence `rationale` summarising the outcome.",
)
data class AlignmentFindings(
    @field:Valid
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Misalignments between contract and generated BPMN. Empty list means fully aligned. " +
            "Maximum 200 entries.",
    )
    val issues: List<AlignmentIssue> = emptyList(),
    @field:NotBlank
    @field:Size(max = 1000)
    @get:JsonPropertyDescription("Short (1-2 sentence) summary of the alignment outcome.")
    val rationale: String,
)

@JsonClassDescription(
    "A single alignment issue: either a generated BPMN element not justified by the contract, " +
        "or a contract item not fully covered by the generated BPMN.",
)
data class AlignmentIssue(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "ID of the BPMN element OR contract item this issue refers to. Use an existing id from the inputs.",
    )
    val elementId: String,
    @get:JsonPropertyDescription(
        "Issue classification. Use ASSUMED or UNSUPPORTED for unjustified generated elements; " +
            "PARTIALLY_COVERED or MISSING for under-covered contract items.",
    )
    val classification: AlignmentClassification,
)

data class BpmnAlignmentReport(
    val verdict: AlignmentVerdict,
    val bpmnSummary: BpmnDefinitionSummary,
    val issues: List<AlignmentIssue>,
    val rationale: String,
)

class BpmnAlignmentException(
    message: String,
    // `null` when the alignment model itself failed to produce a structured response (the LLM
    // call threw, not "the LLM examined the BPMN and found a problem"). Phase 5 (#220) lets
    // the exception convey the failure-type distinction without smuggling it through a synthetic
    // AlignmentIssue.
    val report: BpmnAlignmentReport?,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
