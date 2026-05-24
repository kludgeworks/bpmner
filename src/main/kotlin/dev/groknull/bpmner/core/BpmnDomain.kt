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
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.api.PklProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import dev.groknull.bpmner.api.BpmnBoundaryEvent as ApiBpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnBusinessRuleTask as ApiBpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnDefinition as ApiBpmnDefinition
import dev.groknull.bpmner.api.BpmnEdge as ApiBpmnEdge
import dev.groknull.bpmner.api.BpmnEndEvent as ApiBpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition as ApiBpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnErrorRef as ApiBpmnErrorRef
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition as ApiBpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationRef as ApiBpmnEscalationRef
import dev.groknull.bpmner.api.BpmnEventDefinition as ApiBpmnEventDefinition
import dev.groknull.bpmner.api.BpmnExclusiveGateway as ApiBpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent as ApiBpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent as ApiBpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnManualTask as ApiBpmnManualTask
import dev.groknull.bpmner.api.BpmnMessageEventDefinition as ApiBpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnMessageRef as ApiBpmnMessageRef
import dev.groknull.bpmner.api.BpmnNode as ApiBpmnNode
import dev.groknull.bpmner.api.BpmnNoneEventDefinition as ApiBpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnParallelGateway as ApiBpmnParallelGateway
import dev.groknull.bpmner.api.BpmnReceiveTask as ApiBpmnReceiveTask
import dev.groknull.bpmner.api.BpmnRequest as ApiBpmnRequest
import dev.groknull.bpmner.api.BpmnScriptTask as ApiBpmnScriptTask
import dev.groknull.bpmner.api.BpmnSendTask as ApiBpmnSendTask
import dev.groknull.bpmner.api.BpmnServiceTask as ApiBpmnServiceTask
import dev.groknull.bpmner.api.BpmnSignalEventDefinition as ApiBpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnSignalRef as ApiBpmnSignalRef
import dev.groknull.bpmner.api.BpmnStartEvent as ApiBpmnStartEvent
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition as ApiBpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition as ApiBpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUserTask as ApiBpmnUserTask

data class BpmnRequest(
    @get:JsonPropertyDescription("Natural-language description of the workflow to model")
    override val processDescription: String,
    @get:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    override val styleGuide: String? = null,
    @get:JsonPropertyDescription("Optional BPMN output file path. Required for file generation mode.")
    override val outputFile: String? = null,
    override val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    @field:Valid
    @get:JsonPropertyDescription("Ordered answered clarification history for this generation request")
    override val clarificationHistory: List<ClarificationExchange> = emptyList(),
) : ApiBpmnRequest,
    PromptContributor {
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
    override val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable BPMN process name")
    override val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("All BPMN nodes participating in the process graph")
    override val nodes: List<BpmnNode>,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Directed sequence-flow edges connecting node ids")
    override val sequences: List<BpmnEdge>,
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN message declarations referenced by message event definitions")
    override val messages: List<BpmnMessageRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN signal declarations referenced by signal event definitions")
    override val signals: List<BpmnSignalRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN error declarations referenced by error event definitions")
    override val errors: List<BpmnErrorRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Reusable BPMN escalation declarations referenced by escalation event definitions")
    override val escalations: List<BpmnEscalationRef> = emptyList(),
) : ApiBpmnDefinition

data class BpmnMessageRef(
    @field:NotBlank
    override val id: String,
    @field:NotBlank
    override val name: String,
) : ApiBpmnMessageRef

data class BpmnSignalRef(
    @field:NotBlank
    override val id: String,
    @field:NotBlank
    override val name: String,
) : ApiBpmnSignalRef

data class BpmnErrorRef(
    @field:NotBlank
    override val id: String,
    @field:NotBlank
    override val code: String,
    override val name: String? = null,
) : ApiBpmnErrorRef

data class BpmnEscalationRef(
    @field:NotBlank
    override val id: String,
    @field:NotBlank
    override val code: String,
    override val name: String? = null,
) : ApiBpmnEscalationRef

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
sealed interface BpmnEventDefinition : ApiBpmnEventDefinition

data object BpmnNoneEventDefinition :
    BpmnEventDefinition,
    ApiBpmnNoneEventDefinition

data class BpmnTimerEventDefinition(
    override val timerKind: BpmnTimerKind,
    @field:NotBlank
    @get:PklProperty("expression")
    override val expression: String,
) : BpmnEventDefinition,
    ApiBpmnTimerEventDefinition

data class BpmnMessageEventDefinition(
    @field:NotBlank
    override val messageRef: String,
) : BpmnEventDefinition,
    ApiBpmnMessageEventDefinition

data class BpmnSignalEventDefinition(
    @field:NotBlank
    override val signalRef: String,
) : BpmnEventDefinition,
    ApiBpmnSignalEventDefinition

data class BpmnErrorEventDefinition(
    @field:NotBlank
    override val errorRef: String,
) : BpmnEventDefinition,
    ApiBpmnErrorEventDefinition

data class BpmnEscalationEventDefinition(
    @field:NotBlank
    override val escalationRef: String,
) : BpmnEventDefinition,
    ApiBpmnEscalationEventDefinition

data object BpmnTerminateEventDefinition :
    BpmnEventDefinition,
    ApiBpmnTerminateEventDefinition

/**
 * Sealed BPMN node hierarchy. Each subtype corresponds to one BPMN element kind.
 *
 * The Jackson polymorphism annotations keep the LLM-facing JSON shape flat with an
 * explicit `type` discriminator string — identical to the shape produced before the
 * sealed refactor. On deserialize, Jackson dispatches `type` to the matching subtype;
 * on serialize, Jackson writes `type` as the discriminator. Kotlin code dispatches via
 * exhaustive `when (node)` blocks over the sealed subtypes, so the compiler catches any
 * site that forgets to handle a kind when a new subtype is introduced.
 *
 * Each concrete data class also implements its specific [dev.groknull.bpmner.api]
 * counterpart (`api.BpmnUserTask`, `api.BpmnServiceTask`, …) so the rule engine and any
 * future Tier-3 plugin can program against the annotation-free api hierarchy without
 * pulling Jackson/Jakarta into its classpath.
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
sealed interface BpmnNode : ApiBpmnNode {
    override val id: String

    @get:PklProperty("name")
    override val name: String?

    // Narrow the inherited api.BpmnNode.withName(): api.BpmnNode return type so that
    // call sites typed as `core.BpmnNode` see the more specific return type and can pass
    // results to `core.BpmnDefinition.copy(nodes = ...)` without a cast.
    override fun withName(name: String?): BpmnNode
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
    @get:PklProperty("eventDefinition")
    @get:JsonPropertyDescription("Nested BPMN event definition; NONE represents a plain start event")
    override val eventDefinition: BpmnEventDefinition = BpmnNoneEventDefinition,
    @get:JsonPropertyDescription("Whether this start interrupts its enclosing scope; event subprocess starts may set false")
    override val isInterrupting: Boolean = true,
) : BpmnNode,
    ApiBpmnStartEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnUserTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnUserTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnServiceTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnServiceTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnScriptTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnScriptTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnBusinessRuleTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:PklProperty("decisionRef")
    @get:JsonPropertyDescription(
        "Identifier of the decision (e.g. DMN decision id, rule-set name) that this task evaluates. " +
            "Free-form string until a typed decision catalogue exists; non-blank.",
    )
    override val decisionRef: String,
) : BpmnNode,
    ApiBpmnBusinessRuleTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnSendTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:PklProperty("messageRef")
    @get:JsonPropertyDescription(
        "Id of the BpmnMessageRef in the process-level message catalogue that this send task emits.",
    )
    override val messageRef: String,
) : BpmnNode,
    ApiBpmnSendTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

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
    override val messageRef: String,
) : BpmnNode,
    ApiBpmnReceiveTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnManualTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnManualTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnExclusiveGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnExclusiveGateway {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnParallelGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
) : BpmnNode,
    ApiBpmnParallelGateway {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnIntermediateCatchEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    override val eventDefinition: BpmnEventDefinition,
) : BpmnNode,
    ApiBpmnIntermediateCatchEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnIntermediateThrowEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    override val eventDefinition: BpmnEventDefinition,
) : BpmnNode,
    ApiBpmnIntermediateThrowEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnBoundaryEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:PklProperty("attachedToRef")
    @get:JsonPropertyDescription("BPMN id of the activity this boundary event is attached to")
    override val attachedToRef: String,
    @get:PklProperty("cancelActivity")
    @get:JsonPropertyDescription("Whether the boundary event cancels the attached activity when it fires")
    override val cancelActivity: Boolean = true,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    override val eventDefinition: BpmnEventDefinition,
) : BpmnNode,
    ApiBpmnBoundaryEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnEndEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition; NONE represents a plain end event")
    override val eventDefinition: BpmnEventDefinition = BpmnNoneEventDefinition,
) : BpmnNode,
    ApiBpmnEndEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

@JsonClassDescription("Directed BPMN sequence flow with optional label and condition")
data class BpmnEdge(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique sequence-flow id, e.g. Flow_1")
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source node id")
    override val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target node id")
    override val targetRef: String,
    @get:JsonPropertyDescription("Optional human-readable sequence-flow label")
    override val name: String? = null,
    @get:JsonPropertyDescription("Optional sequence-flow condition expression, typically used on gateway branches")
    override val conditionExpression: String? = null,
    @get:JsonPropertyDescription(
        "Mark this edge as the BPMN default flow for an exclusive gateway. Default flows " +
            "are taken when no other branch's condition matches and must not carry a " +
            "condition expression themselves. At most one default flow per gateway.",
    )
    override val isDefault: Boolean = false,
) : ApiBpmnEdge {
    init {
        require(!(isDefault && !conditionExpression.isNullOrBlank())) {
            "BpmnEdge $id: default edge must not carry a condition expression"
        }
    }
}
