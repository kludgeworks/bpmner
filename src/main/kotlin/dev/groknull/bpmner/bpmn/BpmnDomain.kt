/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.springframework.ai.tool.annotation.Tool

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
)

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
    @get:JsonPropertyDescription("Reusable BPMN error declarations referenced by error event definitions")
    val errors: List<BpmnErrorRef> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Text annotations explaining elements (e.g. the item set of a multi-instance task)")
    val annotations: List<BpmnTextAnnotation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Visual BPMN group artifacts. Groups carry no process semantics.")
    val groups: List<BpmnGroup> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Association edges linking text annotations to the flow elements they explain")
    val associations: List<BpmnAssociation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Participants (pools): white-box (processRef set, owns the process) or black-box (external, processRef null)",
    )
    val participants: List<BpmnParticipant> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Lanes partitioning white-box pools by business role/performer")
    val lanes: List<BpmnLane> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Message flows between participants (across pools only)")
    val messageFlows: List<BpmnMessageFlow> = emptyList(),
    // Document-level BPMNDI diagram count surfaced by the XML parser. Not serialized for LLM
    // round-trip: defaulted to 0, no @JsonPropertyDescription, so Jackson treats it as a
    // benign extra field on serialize and an unknown field on deserialize (skipped).
    @field:com.fasterxml.jackson.annotation.JsonIgnore
    val diagramCount: Int = 0,
) {

    /**
     * Model-intrinsic structural validation — checks that are pure properties of the graph
     * topology itself, with no external policy or naming knowledge required.
     *
     * Returns a (possibly empty) list of error messages following the
     * [LaidOutProcessGraph.validateOwnership] idiom: never throws, callers accumulate errors.
     * [dev.groknull.bpmner.conformance.internal.domain.BpmnDefinitionValidator] delegates to this method for
     * these structural checks and handles non-intrinsic policy checks itself.
     *
     * Checks performed:
     * - No duplicate node ids or edge ids in [nodes] / [sequences].
     * - Every edge's [BpmnEdge.sourceRef] and [BpmnEdge.targetRef] resolve to a node id;
     *   a self-referencing edge (sourceRef == targetRef) is also flagged.
     * - At least one top-level [BpmnStartEvent] and at least one top-level [BpmnEndEvent]
     *   (i.e. [BpmnNode.parentRef] == null for both).
     */
    @Tool
    fun validateStructure(): List<String> {
        val nodeIdSet = nodes.map { it.id }.toSet()
        return buildList {
            addAll(duplicateIdErrors(nodes.map { it.id.trim() }, "node"))
            addAll(duplicateIdErrors(sequences.map { it.id.trim() }, "edge"))
            sequences.forEach { edge -> addAll(edgeReferenceErrors(edge, nodeIdSet)) }
            if (nodes.none { it is BpmnStartEvent && it.parentRef == null }) {
                add("definition must contain at least one START_EVENT")
            }
            if (nodes.none { it is BpmnEndEvent && it.parentRef == null }) {
                add("definition must contain at least one END_EVENT")
            }
        }
    }

    private fun duplicateIdErrors(ids: List<String>, kind: String): List<String> = ids.groupBy { it }
        .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
        .keys.map { "duplicate $kind id: $it" }

    private fun edgeReferenceErrors(edge: BpmnEdge, nodeIdSet: Set<String>): List<String> = buildList {
        val label = edge.id.ifBlank { "<blank>" }
        if (edge.sourceRef !in nodeIdSet) add("edge $label sourceRef '${edge.sourceRef}' does not match any node id")
        if (edge.targetRef !in nodeIdSet) add("edge $label targetRef '${edge.targetRef}' does not match any node id")
        if (edge.sourceRef == edge.targetRef) add("edge $label must not self-reference source and target")
    }
}

data class BpmnMessageRef(
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

data class BpmnGroup(
    @field:NotBlank
    val id: String,
    val name: String? = null,
)

@JsonClassDescription("Reusable BPMN event definition carried by an event-position node")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = BpmnNoneEventDefinition::class, name = "NONE"),
    JsonSubTypes.Type(value = BpmnTimerEventDefinition::class, name = "TIMER"),
    JsonSubTypes.Type(value = BpmnMessageEventDefinition::class, name = "MESSAGE"),
    JsonSubTypes.Type(value = BpmnErrorEventDefinition::class, name = "ERROR"),
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

data class BpmnErrorEventDefinition(
    @field:NotBlank
    val errorRef: String,
) : BpmnEventDefinition

data object BpmnTerminateEventDefinition : BpmnEventDefinition

/**
 * Fallback for any event-definition typename the parser sees but doesn't have a typed class
 * for (today: only `bpmn:CompensateEventDefinition`). Absent from the `@JsonSubTypes`
 * registration above: Jackson serialization fails on an instance, so the LLM round-trip
 * cannot accidentally see one. Callers that need to serialize a definition must filter these
 * out first.
 */
data class BpmnUnrecognizedEventDefinition(
    val typeName: String,
) : BpmnEventDefinition

/**
 * Sealed BPMN node hierarchy. Each subtype corresponds to one BPMN element kind.
 *
 * The Jackson polymorphism annotations keep the LLM-facing JSON shape flat with an
 * explicit `type` discriminator string. On deserialize, Jackson dispatches `type` to the
 * matching subtype; on serialize, Jackson writes `type` as the discriminator. Kotlin code
 * dispatches via exhaustive `when (node)` blocks over the sealed subtypes, so the compiler
 * catches any site that forgets to handle a kind when a new subtype is introduced.
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
    JsonSubTypes.Type(value = BpmnInclusiveGateway::class, name = "INCLUSIVE_GATEWAY"),
    JsonSubTypes.Type(value = BpmnParallelGateway::class, name = "PARALLEL_GATEWAY"),
    JsonSubTypes.Type(value = BpmnEventBasedGateway::class, name = "EVENT_BASED_GATEWAY"),
    JsonSubTypes.Type(value = BpmnIntermediateCatchEvent::class, name = "INTERMEDIATE_CATCH_EVENT"),
    JsonSubTypes.Type(value = BpmnIntermediateThrowEvent::class, name = "INTERMEDIATE_THROW_EVENT"),
    JsonSubTypes.Type(value = BpmnBoundaryEvent::class, name = "BOUNDARY_EVENT"),
    JsonSubTypes.Type(value = BpmnEndEvent::class, name = "END_EVENT"),
    JsonSubTypes.Type(value = BpmnSubProcess::class, name = "SUB_PROCESS"),
    JsonSubTypes.Type(value = BpmnCallActivity::class, name = "CALL_ACTIVITY"),
)
sealed interface BpmnNode {
    val id: String
    val name: String?

    /**
     * Id of the [BpmnSubProcess] this node is nested inside, or `null` for a top-level node.
     * `BpmnDefinition` stays flat — subprocess children live in the same flat `nodes` list and
     * carry this back-reference; nesting is reconstructed only when rendering XML.
     */
    val parentRef: String?

    fun withName(name: String?): BpmnNode
}

private const val NODE_ID_DESCRIPTION: String =
    "Unique node id. For contract-realized nodes, use the corresponding `act-…` / `dec-…` / `end-…` " +
        "id from the ProcessContract verbatim. For synthesized routing nodes (process start event, " +
        "converging joins, intermediate routing), use a stable unique id of your choosing (e.g. " +
        "`StartEvent_1`, `Gateway_join_1`). The element kind is carried by `type`, not the id prefix."

private const val NODE_NAME_DESCRIPTION: String =
    "Optional node label. Required for tasks, events, and diverging gateways; omit for converging gateways."

private const val MULTI_INSTANCE_DESCRIPTION: String =
    "Optional multi-instance marker. Set only when the activity runs once per item in a " +
        "collection (a 'for each …' loop); leave null for an ordinary single-run task."

private const val STANDARD_LOOP_DESCRIPTION: String =
    "Optional standard-loop marker. Set only when the activity repeats until a condition is met " +
        "(a while/until/retry loop); leave null for an ordinary single-run task. Distinct from " +
        "multiInstance, which runs once per item in a collection."

private const val PARENT_REF_DESCRIPTION: String =
    "Id of the enclosing subprocess when this node is nested inside one; leave null for a top-level node. " +
        "Nodes stay in the flat list and carry this back-reference rather than being nested."

@JsonClassDescription(
    "Multi-instance loop characteristics: the activity executes once per item in a collection, " +
        "either one at a time (SEQUENTIAL) or all concurrently (PARALLEL).",
)
data class MultiInstanceLoopCharacteristics(
    @get:JsonPropertyDescription(
        "SEQUENTIAL = items handled one at a time (renders isSequential=true); " +
            "PARALLEL = items handled concurrently (renders isSequential=false).",
    )
    val mode: MultiInstanceMode,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Human-readable description of the collection iterated over, e.g. " +
            "\"each line item on the packing slip\". The 'for each X' phrase from the source.",
    )
    val collectionDescription: String,
    @get:JsonPropertyDescription("Optional fixed iteration count when the cardinality is statically known")
    val loopCardinality: Int? = null,
    @get:JsonPropertyDescription("Optional early-exit predicate that stops iteration before all items are processed")
    val completionCondition: String? = null,
)

@JsonClassDescription(
    "Standard loop characteristics: the activity repeats until a condition is met — a while loop " +
        "(testBefore=true, condition tested before each iteration) or an until loop " +
        "(testBefore=false, body runs once then the condition is tested).",
)
data class StandardLoopCharacteristics(
    @get:JsonPropertyDescription(
        "true = while-loop (condition tested before each iteration); " +
            "false = until-loop (body runs once, then the condition is tested).",
    )
    val testBefore: Boolean = true,
    @get:JsonPropertyDescription(
        "Human-readable loop continue/exit condition, e.g. \"payment not yet successful\".",
    )
    val loopCondition: String? = null,
    @get:JsonPropertyDescription("Optional cap on the number of iterations, e.g. retry up to 3 times")
    val loopMaximum: Int? = null,
)

data class BpmnStartEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition; NONE represents a plain start event")
    override val eventDefinition: BpmnEventDefinition = BpmnNoneEventDefinition,
    @get:JsonPropertyDescription("Whether this start interrupts its enclosing scope; event subprocess starts may set false")
    val isInterrupting: Boolean = true,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnUserTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnServiceTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnScriptTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
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
    val messageRef: String,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnManualTask(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnTask {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnExclusiveGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnGateway {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnInclusiveGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnGateway {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnParallelGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnGateway {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnEventBasedGateway(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnGateway {
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnEvent {
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

data class BpmnBoundaryEvent(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN id of the activity this boundary event is attached to")
    val attachedToRef: String,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    override val eventDefinition: BpmnEventDefinition,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnEvent {
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    BpmnEvent {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

@JsonClassDescription(
    "An embedded subprocess: a composite activity containing its own start-to-end flow. Its inner " +
        "nodes and edges stay in the flat definition lists and carry parentRef = this subprocess's id.",
)
data class BpmnSubProcess(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

@JsonClassDescription("BPMN call activity that delegates to another process referenced by id")
data class BpmnCallActivity(
    @field:NotBlank
    @get:JsonPropertyDescription(NODE_ID_DESCRIPTION)
    override val id: String,
    @get:JsonPropertyDescription(NODE_NAME_DESCRIPTION)
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Id of the process this call activity invokes (the called process). The called process is " +
            "defined separately — typically in another file or the runtime catalogue — and is " +
            "referenced here by id; it need not appear in this definition.",
    )
    val calledElement: String,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

/**
 * Fallback for any process element the parser sees but doesn't have a typed Kotlin class for
 * (e.g. `bpmn:Choreography`, `bpmn:Transaction`). The rule engine sees these like any other
 * node and can flag them via `targetElements` matching on [bpmnType]. Absent from the
 * `@JsonSubTypes` registration above: Jackson serialization fails on an instance, so the LLM
 * round-trip cannot accidentally see one. Callers that need to serialize a definition must
 * filter these out first.
 */
data class BpmnUnrecognizedNode(
    override val id: String,
    override val name: String? = null,
    val bpmnType: String,
    override val parentRef: String? = null,
) : BpmnNode {
    override fun withName(name: String?): BpmnNode = copy(name = name)
}

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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    val parentRef: String? = null,
) {
    init {
        require(!(isDefault && !conditionExpression.isNullOrBlank())) {
            "BpmnEdge $id: default edge must not carry a condition expression"
        }
    }
}

@JsonClassDescription("BPMN text annotation: free-text commentary linked to an element by an association")
data class BpmnTextAnnotation(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique text-annotation id, e.g. TextAnnotation_1")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("The annotation's free text, e.g. \"For each line item on the slip\"")
    val text: String,
)

@JsonClassDescription("BPMN association edge linking a text annotation to the element it explains")
data class BpmnAssociation(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique association id, e.g. Association_1")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source element id (the annotated flow element, per BPMN convention)")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target element id (the text annotation)")
    val targetRef: String,
)

@JsonClassDescription("BPMN participant (pool): white-box owns a process, black-box is an external entity")
data class BpmnParticipant(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable participant id, e.g. Participant_OrderSvc")
    val id: String,
    @get:JsonPropertyDescription("Pool label; for a white-box pool this is the process name")
    val name: String? = null,
    @get:JsonPropertyDescription("Id of the process this pool owns; LEAVE NULL for a black-box (external) participant")
    val processRef: String? = null,
)

@JsonClassDescription("BPMN lane: partitions a white-box pool by business role or performer")
data class BpmnLane(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable lane id, e.g. Lane_Sales")
    val id: String,
    @get:JsonPropertyDescription("Lane label — a business role or performer, e.g. \"Sales\"")
    val name: String? = null,
    @get:JsonPropertyDescription(
        "Id of the participant (pool) this lane belongs to; null when the process has lanes but no surrounding collaboration",
    )
    val participantId: String? = null,
    @get:JsonPropertyDescription("Ids of the flow nodes contained in this lane (node ids, not names)")
    val flowNodeRefs: List<String> = emptyList(),
)

@JsonClassDescription("BPMN message flow between two participants (across pools only)")
data class BpmnMessageFlow(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable message-flow id, e.g. MessageFlow_1")
    val id: String,
    @get:JsonPropertyDescription("Optional label, e.g. \"Payment request\"")
    val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription("Source: a flow-element id or a black-box participant id")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target: a flow-element id or a black-box participant id")
    val targetRef: String,
)
