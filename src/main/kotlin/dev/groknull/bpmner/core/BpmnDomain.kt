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

enum class BpmnTimerKind {
    DATE,
    DURATION,
    CYCLE,
}

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
                  EXCLUSIVE_GATEWAY / PARALLEL_GATEWAY / END_EVENT / …). Do not re-encode element
                  type as a prefix in the id.
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
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN message declarations referenced by message event definitions")
    val messages: List<BpmnMessageRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN signal declarations referenced by signal event definitions")
    val signals: List<BpmnSignalRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN error declarations referenced by error event definitions")
    val errors: List<BpmnErrorRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN escalation declarations referenced by escalation event definitions")
    val escalations: List<BpmnEscalationRef> = emptyList(),
)

data class BpmnMessageRef(
    @field:NotBlank
    val id: String,
    @field:NotBlank
    val name: String,
)

data class BpmnSignalRef(
    @field:NotBlank
    val id: String,
    @field:NotBlank
    val name: String,
)

data class BpmnErrorRef(
    @field:NotBlank
    val id: String,
    @field:NotBlank
    val code: String,
    val name: String? = null,
)

data class BpmnEscalationRef(
    @field:NotBlank
    val id: String,
    @field:NotBlank
    val code: String,
    val name: String? = null,
)

@JsonClassDescription("Reusable BPMN event definition carried by an event-position node")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = BpmnNoneEventDefinition::class, name = "NONE"),
    JsonSubTypes.Type(value = BpmnTimerEventDefinition::class, name = "TIMER"),
    JsonSubTypes.Type(value = BpmnMessageEventDefinition::class, name = "MESSAGE"),
    JsonSubTypes.Type(value = BpmnSignalEventDefinition::class, name = "SIGNAL"),
    JsonSubTypes.Type(value = BpmnErrorEventDefinition::class, name = "ERROR"),
    JsonSubTypes.Type(value = BpmnEscalationEventDefinition::class, name = "ESCALATION"),
    JsonSubTypes.Type(value = BpmnTerminateEventDefinition::class, name = "TERMINATE"),
)
sealed interface BpmnEventDefinition

data object BpmnNoneEventDefinition : BpmnEventDefinition

data class BpmnTimerEventDefinition(
    val timerKind: BpmnTimerKind,
    @field:NotBlank
    val expression: String,
) : BpmnEventDefinition

data class BpmnMessageEventDefinition(
    @field:NotBlank
    val messageRef: String,
) : BpmnEventDefinition

data class BpmnSignalEventDefinition(
    @field:NotBlank
    val signalRef: String,
) : BpmnEventDefinition

data class BpmnErrorEventDefinition(
    @field:NotBlank
    val errorRef: String,
) : BpmnEventDefinition

data class BpmnEscalationEventDefinition(
    @field:NotBlank
    val escalationRef: String,
) : BpmnEventDefinition

data object BpmnTerminateEventDefinition : BpmnEventDefinition

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
    JsonSubTypes.Type(value = BpmnScriptTask::class, name = "SCRIPT_TASK"),
    JsonSubTypes.Type(value = BpmnBusinessRuleTask::class, name = "BUSINESS_RULE_TASK"),
    JsonSubTypes.Type(value = BpmnSendTask::class, name = "SEND_TASK"),
    JsonSubTypes.Type(value = BpmnReceiveTask::class, name = "RECEIVE_TASK"),
    JsonSubTypes.Type(value = BpmnManualTask::class, name = "MANUAL_TASK"),
    JsonSubTypes.Type(value = BpmnExclusiveGateway::class, name = "EXCLUSIVE_GATEWAY"),
    JsonSubTypes.Type(value = BpmnParallelGateway::class, name = "PARALLEL_GATEWAY"),
    JsonSubTypes.Type(value = BpmnIntermediateCatchEvent::class, name = "INTERMEDIATE_CATCH_EVENT"),
    JsonSubTypes.Type(value = BpmnIntermediateThrowEvent::class, name = "INTERMEDIATE_THROW_EVENT"),
    JsonSubTypes.Type(value = BpmnBoundaryEvent::class, name = "BOUNDARY_EVENT"),
    JsonSubTypes.Type(value = BpmnEndEvent::class, name = "END_EVENT"),
)
sealed interface BpmnNode {
    val id: String
    val name: String?
}

/**
 * The discriminator string for [node], matching the `type` field in the LLM JSON output
 * and the names declared in `@JsonSubTypes` above.
 *
 * NOTE: the string literals below must stay in sync with the `name` values in the
 * `@JsonSubTypes` annotation on [BpmnNode]. The exhaustive `when` guarantees the compiler
 * catches a *missing arm* when a new subtype is added, but it cannot catch a typo or
 * divergence between these literals and the Jackson annotation values. If you add or
 * rename a subtype, update both lists together.
 */
val BpmnNode.typeName: String
    get() =
        when (this) {
            is BpmnStartEvent -> "START_EVENT"
            is BpmnUserTask -> "USER_TASK"
            is BpmnServiceTask -> "SERVICE_TASK"
            is BpmnScriptTask -> "SCRIPT_TASK"
            is BpmnBusinessRuleTask -> "BUSINESS_RULE_TASK"
            is BpmnSendTask -> "SEND_TASK"
            is BpmnReceiveTask -> "RECEIVE_TASK"
            is BpmnManualTask -> "MANUAL_TASK"
            is BpmnExclusiveGateway -> "EXCLUSIVE_GATEWAY"
            is BpmnParallelGateway -> "PARALLEL_GATEWAY"
            is BpmnIntermediateCatchEvent -> "INTERMEDIATE_CATCH_EVENT"
            is BpmnIntermediateThrowEvent -> "INTERMEDIATE_THROW_EVENT"
            is BpmnBoundaryEvent -> "BOUNDARY_EVENT"
            is BpmnEndEvent -> "END_EVENT"
        }

/**
 * Returns a new [BpmnNode] of the same concrete subtype with [name] replaced. Sealed
 * interfaces have no synthetic `copy`, so this helper dispatches across the subtypes
 * exhaustively. Used by repair operations that rename a node while preserving its kind.
 */
@Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
fun BpmnNode.withName(name: String?): BpmnNode =
    when (this) {
        is BpmnStartEvent -> copy(name = name)
        is BpmnUserTask -> copy(name = name)
        is BpmnServiceTask -> copy(name = name)
        is BpmnScriptTask -> copy(name = name)
        is BpmnBusinessRuleTask -> copy(name = name)
        is BpmnSendTask -> copy(name = name)
        is BpmnReceiveTask -> copy(name = name)
        is BpmnManualTask -> copy(name = name)
        is BpmnExclusiveGateway -> copy(name = name)
        is BpmnParallelGateway -> copy(name = name)
        is BpmnIntermediateCatchEvent -> copy(name = name)
        is BpmnIntermediateThrowEvent -> copy(name = name)
        is BpmnBoundaryEvent -> copy(name = name)
        is BpmnEndEvent -> copy(name = name)
    }

/**
 * True when [node] is one of the BPMN task subtypes. Used by callers that need to
 * dispatch over "any task" without enumerating every task kind individually (e.g. the
 * naming policy that requires a name on every task, the transparency check, repair
 * handlers that operate on tasks but not events or gateways). The exhaustive `when`
 * forces every new task subtype to declare its membership when added.
 */
fun BpmnNode.isTask(): Boolean =
    when (this) {
        is BpmnUserTask,
        is BpmnServiceTask,
        is BpmnScriptTask,
        is BpmnBusinessRuleTask,
        is BpmnSendTask,
        is BpmnReceiveTask,
        is BpmnManualTask,
        -> true

        is BpmnStartEvent,
        is BpmnExclusiveGateway,
        is BpmnParallelGateway,
        is BpmnIntermediateCatchEvent,
        is BpmnIntermediateThrowEvent,
        is BpmnBoundaryEvent,
        is BpmnEndEvent,
        -> false
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
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition; NONE represents a plain start event")
    val eventDefinition: BpmnEventDefinition = BpmnNoneEventDefinition,
    @get:JsonPropertyDescription("Whether this start interrupts its enclosing scope; event subprocess starts may set false")
    val isInterrupting: Boolean = true,
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

data class BpmnScriptTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnBusinessRuleTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Identifier of the decision (e.g. DMN decision id, rule-set name) that this task evaluates. " +
            "Free-form string until a typed decision catalogue exists; non-blank.",
    )
    val decisionRef: String,
) : BpmnNode

data class BpmnSendTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Id of the BpmnMessageRef in the process-level message catalogue that this send task emits.",
    )
    val messageRef: String,
) : BpmnNode

data class BpmnReceiveTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Id of the BpmnMessageRef in the process-level message catalogue that this receive task waits for.",
    )
    val messageRef: String,
) : BpmnNode

data class BpmnManualTask(
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

data class BpmnParallelGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode

data class BpmnIntermediateCatchEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    val eventDefinition: BpmnEventDefinition,
) : BpmnNode

data class BpmnIntermediateThrowEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    val eventDefinition: BpmnEventDefinition,
) : BpmnNode

data class BpmnBoundaryEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN id of the activity this boundary event is attached to")
    val attachedToRef: String,
    @get:JsonPropertyDescription("Whether the boundary event cancels the attached activity when it fires")
    val cancelActivity: Boolean = true,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    val eventDefinition: BpmnEventDefinition,
) : BpmnNode

data class BpmnEndEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition; NONE represents a plain end event")
    val eventDefinition: BpmnEventDefinition = BpmnNoneEventDefinition,
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
    @get:JsonPropertyDescription(
        "Mark this edge as the BPMN default flow for an exclusive gateway. Default flows " +
            "are taken when no other branch's condition matches and must not carry a " +
            "condition expression themselves. At most one default flow per gateway.",
    )
    val isDefault: Boolean = false,
) {
    init {
        require(!(isDefault && !conditionExpression.isNullOrBlank())) {
            "BpmnEdge $id: default edge must not carry a condition expression"
        }
    }
}
