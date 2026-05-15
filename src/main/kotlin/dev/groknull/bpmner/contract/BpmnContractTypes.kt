package dev.groknull.bpmner.contract

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.core.AlignmentClassification
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

@JsonClassDescription("Source-grounded process contract extracted before BPMN generation")
data class ProcessContract(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable contract id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable process name")
    val processName: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Concise process summary")
    val summary: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Process start trigger derived from the source input")
    val trigger: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding the trigger in source evidence")
    val triggerTraceLinks: List<TraceLink> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Activities required by the process contract")
    val activities: List<ContractActivity>,
    @field:Valid
    @get:JsonPropertyDescription("Decisions and branch points required by the process contract")
    val decisions: List<ContractDecision> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Actors referenced by the process contract")
    val actors: List<ContractActor> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Artifacts referenced by the process contract")
    val artifacts: List<ContractArtifact> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Required process end states")
    val endStates: List<ContractEndState>,
    @field:Valid
    @get:JsonPropertyDescription("Assumptions made while extracting the contract")
    val assumptions: List<ContractAssumption> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Trace links from contract elements to source evidence")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Activity required by the extracted process contract")
data class ContractActivity(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable activity id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business activity name")
    val name: String,
    @get:JsonPropertyDescription("Optional actor id responsible for the activity")
    val actorId: String? = null,
    @get:JsonPropertyDescription("Artifact ids consumed by the activity")
    val inputArtifactIds: List<String> = emptyList(),
    @get:JsonPropertyDescription("Artifact ids produced by the activity")
    val outputArtifactIds: List<String> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this activity")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Decision required by the extracted process contract")
data class ContractDecision(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable decision id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business decision question")
    val question: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Branches that can be taken from this decision")
    val branches: List<ContractBranch>,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this decision")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Branch from a contract decision")
data class ContractBranch(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable branch id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Branch label")
    val label: String,
    @get:JsonPropertyDescription("Optional condition expression that selects this branch")
    val condition: String? = null,
)

@JsonClassDescription("Actor referenced by the extracted process contract")
data class ContractActor(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable actor id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Actor name")
    val name: String,
    @get:JsonPropertyDescription("Optional actor role or responsibility")
    val role: String? = null,
)

@JsonClassDescription("Artifact referenced by the extracted process contract")
data class ContractArtifact(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable artifact id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Artifact name")
    val name: String,
    @get:JsonPropertyDescription("Optional artifact description")
    val description: String? = null,
)

@JsonClassDescription("Required end state for the extracted process contract")
data class ContractEndState(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable end-state id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("End-state name")
    val name: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this end state")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Assumption made while extracting the process contract")
data class ContractAssumption(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable assumption id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Assumption text")
    val text: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this assumption")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Trace link between generated, contract, or source elements")
data class TraceLink(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable trace link id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source element or evidence id")
    val sourceId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target element id")
    val targetId: String,
    @get:JsonPropertyDescription("Alignment classification for this trace link")
    val classification: AlignmentClassification = AlignmentClassification.SUPPORTED,
    @get:JsonPropertyDescription("Evidence ids supporting this trace link")
    val evidenceIds: List<String> = emptyList(),
)
