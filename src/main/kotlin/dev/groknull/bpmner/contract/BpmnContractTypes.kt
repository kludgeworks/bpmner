/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

@JsonClassDescription("Source-grounded process contract extracted before BPMN generation")
data class ProcessContract(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable contract id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Human-readable process name")
    val processName: String,
    @field:NotBlank
    @field:Size(max = 1000)
    @get:JsonPropertyDescription("Concise process summary")
    val summary: String,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Process start trigger derived from the source input")
    val trigger: String,
    @field:Valid
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Source ids grounding the trigger in source evidence")
    val triggerSourceIds: List<String> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Activities required by the process contract")
    val activities: List<ContractActivity>,
    @field:Valid
    @field:Size(max = 100)
    @get:JsonPropertyDescription("Decisions and branch points required by the process contract")
    val decisions: List<ContractDecision> = emptyList(),
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Actors referenced by the process contract")
    val actors: List<ContractActor> = emptyList(),
    @field:Valid
    @field:Size(max = 100)
    @get:JsonPropertyDescription("Artifacts referenced by the process contract")
    val artifacts: List<ContractArtifact> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Required process end states")
    val endStates: List<ContractEndState>,
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Assumptions made while extracting the contract")
    val assumptions: List<ContractAssumption> = emptyList(),
)

@JsonClassDescription("Activity required by the extracted process contract")
data class ContractActivity(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable activity id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Activity name from the workflow")
    val name: String,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Optional actor id responsible for the activity")
    val actorId: String? = null,
    @field:Size(max = 10)
    @get:JsonPropertyDescription(
        "Source ids grounding this activity in evidence. Each is an assessment evidence id, " +
            "a clarification questionId, or a literal input-text marker.",
    )
    val sourceIds: List<String> = emptyList(),
)

@JsonClassDescription("Decision required by the extracted process contract")
data class ContractDecision(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable decision id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Decision question from the workflow")
    val question: String,
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Branches that can be taken from this decision")
    val branches: List<ContractBranch>,
    @field:Size(max = 10)
    @get:JsonPropertyDescription("Source ids grounding this decision in evidence.")
    val sourceIds: List<String> = emptyList(),
)

@JsonClassDescription("Branch from a contract decision")
data class ContractBranch(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable branch id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Branch label")
    val label: String,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Optional condition expression that selects this branch")
    val condition: String? = null,
)

@JsonClassDescription("Actor referenced by the extracted process contract")
data class ContractActor(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable actor id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Actor name")
    val name: String,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Optional actor role or responsibility")
    val role: String? = null,
)

@JsonClassDescription("Artifact referenced by the extracted process contract")
data class ContractArtifact(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable artifact id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Artifact name")
    val name: String,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Optional artifact description")
    val description: String? = null,
)

@JsonClassDescription("Required end state for the extracted process contract")
data class ContractEndState(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable end-state id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("End-state name")
    val name: String,
    @field:Size(max = 10)
    @get:JsonPropertyDescription("Source ids grounding this end state in evidence.")
    val sourceIds: List<String> = emptyList(),
)

@JsonClassDescription("Assumption made while extracting the process contract")
data class ContractAssumption(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable assumption id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 1000)
    @get:JsonPropertyDescription("Assumption text")
    val text: String,
    @field:Size(max = 10)
    @get:JsonPropertyDescription("Source ids grounding this assumption in evidence.")
    val sourceIds: List<String> = emptyList(),
)
