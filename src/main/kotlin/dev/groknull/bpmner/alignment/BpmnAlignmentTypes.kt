/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.alignment.AlignmentClassification
import dev.groknull.bpmner.bpmn.BpmnAssociation
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnLane
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnParticipant
import dev.groknull.bpmner.bpmn.BpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics
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
    @get:JsonPropertyDescription("Pools: the white-box process pool and any black-box external participants")
    val participants: List<BpmnParticipant> = emptyList(),
    @get:JsonPropertyDescription("Lanes assigning flow nodes to the actor that performs them")
    val lanes: List<BpmnLane> = emptyList(),
    @get:JsonPropertyDescription("Message flows crossing a pool boundary — the process's external interactions")
    val messageFlows: List<BpmnMessageFlow> = emptyList(),
    @get:JsonPropertyDescription("Text annotations, e.g. the item set documented on a multi-instance task")
    val annotations: List<BpmnTextAnnotation> = emptyList(),
    @get:JsonPropertyDescription("Associations linking annotations to the elements they document")
    val associations: List<BpmnAssociation> = emptyList(),
    @get:JsonPropertyDescription("Message catalogue: names referenced by message events and message flows")
    val messages: List<BpmnMessageRef> = emptyList(),
    @get:JsonPropertyDescription("Error catalogue: codes referenced by error events")
    val errors: List<BpmnErrorRef> = emptyList(),
    @get:JsonPropertyDescription("Signal catalogue: names referenced by signal events")
    val signals: List<BpmnSignalRef> = emptyList(),
    @get:JsonPropertyDescription("Escalation catalogue: codes referenced by escalation events")
    val escalations: List<BpmnEscalationRef> = emptyList(),
) {
    companion object {
        /**
         * `BpmnDefinition` fields deliberately NOT carried into the summary, each with its reason.
         *
         * The summary is the only view of the generated diagram the alignment model ever sees, so a
         * field missing from it is a field the check cannot possibly flag. Omit lanes and pools and
         * a diagram that drops every actor assignment still reports ALIGNED; omit message flows and
         * so does one that drops every external interaction (issue #744).
         *
         * Omission is therefore a decision that must be recorded, not an accident of which fields
         * someone happened to map. `BpmnSummarizerCoverageTest` asserts this map plus the carried
         * fields exhaustively partition `BpmnDefinition`, so a newly added field cannot silently
         * bypass the gate: it either shows up in the summary or it is declared here with a reason.
         */
        private const val NO_CONTRACT_COUNTERPART =
            "ProcessContract models no data artifacts, so these cannot be compared to it"

        val OMITTED_DEFINITION_FIELDS: Map<String, String> = mapOf(
            "groups" to
                "purely visual grouping with no ProcessContract counterpart, so nothing to align against",
            "dataObjectReferences" to NO_CONTRACT_COUNTERPART,
            "dataStoreReferences" to NO_CONTRACT_COUNTERPART,
            "dataInputAssociations" to NO_CONTRACT_COUNTERPART,
            "dataOutputAssociations" to NO_CONTRACT_COUNTERPART,
            "diagramCount" to
                "diagram-interchange bookkeeping; carries no semantic content to align",
        )
    }
}

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
    @get:JsonPropertyDescription("Multi-instance loop characteristics, present on a task that runs once per item")
    val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @get:JsonPropertyDescription("Standard-loop characteristics, present on a task that repeats until a condition is met")
    val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription("Id of the activity this boundary event is attached to, present on boundary events only")
    val attachedToRef: String? = null,
    @get:JsonPropertyDescription("Nested BPMN event definition, present on events only")
    val eventDefinition: BpmnEventDefinition? = null,
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
