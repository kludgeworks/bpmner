/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

enum class GenerationMode {
    SINGLE_SHOT,
    INTERACTIVE,
}

data class BpmnRequest(
    @get:JsonPropertyDescription("Natural-language description of the workflow to model")
    val processDescription: String,
    @get:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    val styleGuide: String? = null,
    @get:JsonPropertyDescription("Optional BPMN output file path. Required for file generation mode.")
    val outputFile: String? = null,
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    @field:Valid
    @get:JsonPropertyDescription("Ordered answered clarification history for this generation request")
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
) : PromptContributor {
    override fun contribution(): String =
        buildString {
            appendLine(
                """
                You are a BPMN process design expert. Given a workflow description — business, automated,
                technical, scientific, or personal — generate a typed BPMN process definition object that
                can be converted to valid BPMN 2.0 XML.

                Rules:
                - Return a single process definition object with processId, processName, nodes, and sequences.
                - Every node id and sequence id must be unique.
                - Every sequence sourceRef and targetRef must reference an existing node id.
                - Include at least one START_EVENT and one END_EVENT.
                - Use clear, descriptive names on tasks and events that faithfully reflect the source workflow.
                - Name diverging gateways as decision questions; leave converging gateways unnamed.
                - **Identity rule:** when a BPMN node realizes a ContractActivity / ContractDecision /
                  ContractEndState from the source ProcessContract, use the contract element's id verbatim
                  as the BPMN node id (e.g. `act-extract-contract`, `dec-readiness`, `end-aborted-repair`).
                  Synthesized nodes that do not realize a contract element (routing-only converging joins,
                  the process start event, intermediate routing nodes) use stable unique ids of your choosing
                  (e.g. `StartEvent_1`, `Gateway_join_1`).
                - The BPMN node type is carried by the `type` field (USER_TASK / SERVICE_TASK /
                  EXCLUSIVE_GATEWAY / END_EVENT / …). Do not re-encode element type as a prefix in the id.
                - Keep process topology coherent with no dangling references.
                - A sequence flow with `sourceRef == targetRef` is forbidden. Back-edges to earlier
                  elements (different sourceRef and targetRef where the target has already been visited)
                  are valid and required to encode iterative loops.
                - Use conditionExpression on conditional gateway branches when needed.

                If you receive validation errors, fix them and return the full corrected object.
                """.trimIndent(),
            )
            if (styleGuide != null) {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## Style guide")
                appendLine()
                appendLine(styleGuide)
            }
        }
}

@JsonClassDescription("Typed BPMN process definition describing the semantic topology of a workflow")
data class BpmnDefinition(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable BPMN process id, e.g. Process_1")
    val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable BPMN process name")
    val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("All BPMN nodes participating in the process graph")
    val nodes: List<BpmnNode>,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Directed sequence-flow edges connecting node ids")
    val sequences: List<BpmnEdge>,
)

/**
 * Sealed BPMN node hierarchy. Each subtype corresponds to one BPMN element kind.
 *
 * The Jackson polymorphism annotations keep the LLM-facing JSON shape flat with an
 * explicit `type` discriminator string — identical to the shape produced before the
 * sealed refactor. On deserialize, Jackson dispatches `type` to the matching subtype;
 * on serialize, Jackson writes `type` as the discriminator. Kotlin code dispatches via
 * exhaustive `when (node)` blocks over the sealed subtypes, so the compiler catches any
 * site that forgets to handle a kind when a new subtype is introduced.
 */
@JsonClassDescription("BPMN node with semantic type")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = BpmnStartEvent::class, name = "START_EVENT"),
    JsonSubTypes.Type(value = BpmnUserTask::class, name = "USER_TASK"),
    JsonSubTypes.Type(value = BpmnServiceTask::class, name = "SERVICE_TASK"),
    JsonSubTypes.Type(value = BpmnExclusiveGateway::class, name = "EXCLUSIVE_GATEWAY"),
    JsonSubTypes.Type(value = BpmnEndEvent::class, name = "END_EVENT"),
)
sealed interface BpmnNode {
    val id: String
    val name: String?
}

/**
 * The discriminator string for [node], matching the `type` field in the LLM JSON output
 * and the names declared in `@JsonSubTypes` above. Single source of truth; exhaustive `when`
 * guarantees the compiler catches missing arms when a new subtype is added.
 */
val BpmnNode.typeName: String
    get() =
        when (this) {
            is BpmnStartEvent -> "START_EVENT"
            is BpmnUserTask -> "USER_TASK"
            is BpmnServiceTask -> "SERVICE_TASK"
            is BpmnExclusiveGateway -> "EXCLUSIVE_GATEWAY"
            is BpmnEndEvent -> "END_EVENT"
        }

/**
 * Returns a new [BpmnNode] of the same concrete subtype with [name] replaced. Sealed
 * interfaces have no synthetic `copy`, so this helper dispatches across the subtypes
 * exhaustively. Used by repair operations that rename a node while preserving its kind.
 */
fun BpmnNode.withName(name: String?): BpmnNode =
    when (this) {
        is BpmnStartEvent -> copy(name = name)
        is BpmnUserTask -> copy(name = name)
        is BpmnServiceTask -> copy(name = name)
        is BpmnExclusiveGateway -> copy(name = name)
        is BpmnEndEvent -> copy(name = name)
    }

private const val NODE_ID_DESCRIPTION: String =
    "Unique node id. For contract-realized nodes, use the corresponding `act-…` / `dec-…` / `end-…` " +
        "id from the ProcessContract verbatim. For synthesized routing nodes (process start event, " +
        "converging joins, intermediate routing), use a stable unique id of your choosing (e.g. " +
        "`StartEvent_1`, `Gateway_join_1`). The element kind is carried by `type`, not the id prefix."

private const val NODE_NAME_DESCRIPTION: String =
    "Optional node label. Required for tasks, events, and diverging gateways; omit for converging gateways."

data class BpmnStartEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnUserTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnServiceTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnExclusiveGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnEndEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

@JsonClassDescription("Directed BPMN sequence flow with optional label and condition")
data class BpmnEdge(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique sequence-flow id, e.g. Flow_1")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source node id")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target node id")
    val targetRef: String,
    @get:JsonPropertyDescription("Optional human-readable sequence-flow label")
    val name: String? = null,
    @get:JsonPropertyDescription("Optional sequence-flow condition expression, typically used on gateway branches")
    val conditionExpression: String? = null,
)
