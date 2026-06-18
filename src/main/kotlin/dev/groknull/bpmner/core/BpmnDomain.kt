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
import dev.groknull.bpmner.api.DataFlowDirection
import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.api.MultiInstanceMode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import dev.groknull.bpmner.api.BpmnAssociation as ApiBpmnAssociation
import dev.groknull.bpmner.api.BpmnBoundaryEvent as ApiBpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnBusinessRuleTask as ApiBpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnCallActivity as ApiBpmnCallActivity
import dev.groknull.bpmner.api.BpmnDataAssociation as ApiBpmnDataAssociation
import dev.groknull.bpmner.api.BpmnDataObject as ApiBpmnDataObject
import dev.groknull.bpmner.api.BpmnDataStore as ApiBpmnDataStore
import dev.groknull.bpmner.api.BpmnDefinition as ApiBpmnDefinition
import dev.groknull.bpmner.api.BpmnEdge as ApiBpmnEdge
import dev.groknull.bpmner.api.BpmnEndEvent as ApiBpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition as ApiBpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnErrorRef as ApiBpmnErrorRef
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition as ApiBpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationRef as ApiBpmnEscalationRef
import dev.groknull.bpmner.api.BpmnEventBasedGateway as ApiBpmnEventBasedGateway
import dev.groknull.bpmner.api.BpmnEventDefinition as ApiBpmnEventDefinition
import dev.groknull.bpmner.api.BpmnExclusiveGateway as ApiBpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnGroup as ApiBpmnGroup
import dev.groknull.bpmner.api.BpmnInclusiveGateway as ApiBpmnInclusiveGateway
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent as ApiBpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent as ApiBpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnLane as ApiBpmnLane
import dev.groknull.bpmner.api.BpmnManualTask as ApiBpmnManualTask
import dev.groknull.bpmner.api.BpmnMessageEventDefinition as ApiBpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnMessageFlow as ApiBpmnMessageFlow
import dev.groknull.bpmner.api.BpmnMessageRef as ApiBpmnMessageRef
import dev.groknull.bpmner.api.BpmnNode as ApiBpmnNode
import dev.groknull.bpmner.api.BpmnNoneEventDefinition as ApiBpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnParallelGateway as ApiBpmnParallelGateway
import dev.groknull.bpmner.api.BpmnParticipant as ApiBpmnParticipant
import dev.groknull.bpmner.api.BpmnReceiveTask as ApiBpmnReceiveTask
import dev.groknull.bpmner.api.BpmnRequest as ApiBpmnRequest
import dev.groknull.bpmner.api.BpmnScriptTask as ApiBpmnScriptTask
import dev.groknull.bpmner.api.BpmnSendTask as ApiBpmnSendTask
import dev.groknull.bpmner.api.BpmnServiceTask as ApiBpmnServiceTask
import dev.groknull.bpmner.api.BpmnSignalEventDefinition as ApiBpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnSignalRef as ApiBpmnSignalRef
import dev.groknull.bpmner.api.BpmnStartEvent as ApiBpmnStartEvent
import dev.groknull.bpmner.api.BpmnSubProcess as ApiBpmnSubProcess
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition as ApiBpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTextAnnotation as ApiBpmnTextAnnotation
import dev.groknull.bpmner.api.BpmnTimerEventDefinition as ApiBpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUnrecognizedEventDefinition as ApiBpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.api.BpmnUnrecognizedNode as ApiBpmnUnrecognizedNode
import dev.groknull.bpmner.api.BpmnUserTask as ApiBpmnUserTask
import dev.groknull.bpmner.api.MultiInstanceLoopCharacteristics as ApiMultiInstanceLoopCharacteristics
import dev.groknull.bpmner.api.StandardLoopCharacteristics as ApiStandardLoopCharacteristics

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
    // Contributes only the per-request style guide. BPMN generation rules are owned elsewhere:
    // node-id / type-prefix conventions by NODE_ID_DESCRIPTION on FlatBpmnNode; id-uniqueness,
    // reference resolution, and the >=1 START/END requirement by BpmnDefinitionValidator;
    // sourceRef!=targetRef and conditionExpression guidance by generate_bpmn.jinja. Each template
    // states its own role, so no system-level role framing is added here.
    override fun contribution(): String = styleGuide?.let { "## Style guide\n\n$it" } ?: ""
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
    @field:Valid
    @get:JsonPropertyDescription("Text annotations explaining elements (e.g. the item set of a multi-instance task)")
    override val annotations: List<BpmnTextAnnotation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Visual BPMN group artifacts. Groups carry no process semantics.")
    override val groups: List<BpmnGroup> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Association edges linking text annotations to the flow elements they explain")
    override val associations: List<BpmnAssociation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Data objects (transient information) flowing through the process")
    override val dataObjects: List<BpmnDataObject> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Data stores (persisted information: databases, files, queues) the process reads or writes")
    override val dataStores: List<BpmnDataStore> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Read/write links between activities and data objects/stores")
    override val dataAssociations: List<BpmnDataAssociation> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription(
        "Participants (pools): white-box (processRef set, owns the process) or black-box (external, processRef null)",
    )
    override val participants: List<BpmnParticipant> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Lanes partitioning white-box pools by business role/performer")
    override val lanes: List<BpmnLane> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Message flows between participants (across pools only)")
    override val messageFlows: List<BpmnMessageFlow> = emptyList(),
    // Document-level BPMNDI diagram count surfaced by the XML parser. Not serialized for LLM
    // round-trip: defaulted to 0, no @JsonPropertyDescription, so Jackson treats it as a
    // benign extra field on serialize and an unknown field on deserialize (skipped).
    @field:com.fasterxml.jackson.annotation.JsonIgnore
    override val diagramCount: Int = 0,
) : ApiBpmnDefinition {

    /**
     * Model-intrinsic structural validation — checks that are pure properties of the graph
     * topology itself, with no external policy or naming knowledge required.
     *
     * Returns a (possibly empty) list of error messages following the
     * [LaidOutProcessGraph.validateOwnership] idiom: never throws, callers accumulate errors.
     *
     * The three checks mirror the identically-named predicates that lived in
     * `validation/BpmnDefinitionValidator` before this stage; `BpmnDefinitionValidator` now
     * delegates to this method rather than duplicating the logic.
     *
     * Checks performed:
     * - No duplicate node ids or edge ids in [nodes] / [sequences].
     * - Every edge's [BpmnEdge.sourceRef] and [BpmnEdge.targetRef] resolve to a node id;
     *   a self-referencing edge (sourceRef == targetRef) is also flagged.
     * - At least one top-level [BpmnStartEvent] and at least one top-level [BpmnEndEvent]
     *   (i.e. [BpmnNode.parentRef] == null for both).
     */
    fun validateStructure(): List<String> = buildList {
        // Duplicate ids
        val nodeIds = nodes.map { it.id.trim() }
        val edgeIds = sequences.map { it.id.trim() }
        nodeIds.groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys.forEach { add("duplicate node id: $it") }
        edgeIds.groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys.forEach { add("duplicate edge id: $it") }

        // Edge reference integrity
        val nodeIdSet = nodes.map { it.id }.toSet()
        sequences.forEach { edge ->
            val label = edge.id.ifBlank { "<blank>" }
            if (edge.sourceRef !in nodeIdSet) {
                add("edge $label sourceRef '${edge.sourceRef}' does not match any node id")
            }
            if (edge.targetRef !in nodeIdSet) {
                add("edge $label targetRef '${edge.targetRef}' does not match any node id")
            }
            if (edge.sourceRef == edge.targetRef) {
                add("edge $label must not self-reference source and target")
            }
        }

        // Required top-level events
        if (nodes.none { it is BpmnStartEvent && it.parentRef == null }) {
            add("definition must contain at least one START_EVENT")
        }
        if (nodes.none { it is BpmnEndEvent && it.parentRef == null }) {
            add("definition must contain at least one END_EVENT")
        }
    }
}

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

data class BpmnGroup(
    @field:NotBlank
    override val id: String,
    override val name: String? = null,
) : ApiBpmnGroup

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
 * Fallback for any event-definition typename the parser sees but doesn't have a typed class
 * for (today: only `bpmn:CompensateEventDefinition`). Absent from the `@JsonSubTypes`
 * registration above: Jackson serialization fails on an instance, so the LLM round-trip
 * cannot accidentally see one. Callers that need to serialize a definition must filter these
 * out first.
 */
data class BpmnUnrecognizedEventDefinition(
    override val typeName: String,
) : BpmnEventDefinition,
    ApiBpmnUnrecognizedEventDefinition

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
sealed interface BpmnNode : ApiBpmnNode {
    override val id: String

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
    override val mode: MultiInstanceMode,
    @field:NotBlank
    @get:JsonPropertyDescription(
        "Human-readable description of the collection iterated over, e.g. " +
            "\"each line item on the packing slip\". The 'for each X' phrase from the source.",
    )
    override val collectionDescription: String,
    @get:JsonPropertyDescription("Optional fixed iteration count when the cardinality is statically known")
    override val loopCardinality: Int? = null,
    @get:JsonPropertyDescription("Optional early-exit predicate that stops iteration before all items are processed")
    override val completionCondition: String? = null,
) : ApiMultiInstanceLoopCharacteristics

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
    override val testBefore: Boolean = true,
    @get:JsonPropertyDescription(
        "Human-readable loop continue/exit condition, e.g. \"payment not yet successful\".",
    )
    override val loopCondition: String? = null,
    @get:JsonPropertyDescription("Optional cap on the number of iterations, e.g. retry up to 3 times")
    override val loopMaximum: Int? = null,
) : ApiStandardLoopCharacteristics

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
    override val isInterrupting: Boolean = true,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @get:JsonPropertyDescription(
        "Identifier of the decision (e.g. DMN decision id, rule-set name) that this task evaluates. " +
            "Free-form string until a typed decision catalogue exists; non-blank.",
    )
    override val decisionRef: String,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @get:JsonPropertyDescription(
        "Id of the BpmnMessageRef in the process-level message catalogue that this send task emits.",
    )
    override val messageRef: String,
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @field:Valid
    @get:JsonPropertyDescription(MULTI_INSTANCE_DESCRIPTION)
    override val multiInstance: MultiInstanceLoopCharacteristics? = null,
    @field:Valid
    @get:JsonPropertyDescription(STANDARD_LOOP_DESCRIPTION)
    override val standardLoop: StandardLoopCharacteristics? = null,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    ApiBpmnExclusiveGateway {
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
    ApiBpmnInclusiveGateway {
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
    ApiBpmnParallelGateway {
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
    ApiBpmnEventBasedGateway {
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @get:JsonPropertyDescription("BPMN id of the activity this boundary event is attached to")
    override val attachedToRef: String,
    @get:JsonPropertyDescription("Whether the boundary event cancels the attached activity when it fires")
    override val cancelActivity: Boolean = true,
    @field:Valid
    @get:JsonPropertyDescription("Nested BPMN event definition")
    override val eventDefinition: BpmnEventDefinition,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    ApiBpmnEndEvent {
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
    @get:JsonPropertyDescription(
        "false for an ordinary embedded subprocess; true for an event subprocess (triggered by an " +
            "inner event start rather than an incoming sequence flow).",
    )
    override val triggeredByEvent: Boolean = false,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    ApiBpmnSubProcess {
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
    override val calledElement: String,
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : BpmnNode,
    ApiBpmnCallActivity {
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
    override val bpmnType: String,
    override val parentRef: String? = null,
) : BpmnNode,
    ApiBpmnUnrecognizedNode {
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
    @get:JsonPropertyDescription(PARENT_REF_DESCRIPTION)
    override val parentRef: String? = null,
) : ApiBpmnEdge {
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
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("The annotation's free text, e.g. \"For each line item on the slip\"")
    override val text: String,
) : ApiBpmnTextAnnotation

@JsonClassDescription("BPMN association edge linking a text annotation to the element it explains")
data class BpmnAssociation(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique association id, e.g. Association_1")
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source element id (the annotated flow element, per BPMN convention)")
    override val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target element id (the text annotation)")
    override val targetRef: String,
) : ApiBpmnAssociation

@JsonClassDescription("BPMN data object: transient information flowing through the process")
data class BpmnDataObject(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique data-object id, e.g. DataObject_1")
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business name of the data object, e.g. \"Order\"")
    override val name: String,
) : ApiBpmnDataObject

@JsonClassDescription("BPMN data store: persisted information (database, file, queue) the process reads or writes")
data class BpmnDataStore(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique data-store id, e.g. DataStore_1")
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business name of the data store, e.g. \"Customer database\"")
    override val name: String,
) : ApiBpmnDataStore

@JsonClassDescription("Read/write link between an activity and a data object or store")
data class BpmnDataAssociation(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique data-association id, e.g. DataAssociation_1")
    override val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source activity id (the reader or writer)")
    override val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target data object/store id")
    override val targetRef: String,
    @field:NotNull
    @get:JsonPropertyDescription("READ = activity consumes the data; WRITE = activity produces or updates it")
    override val direction: DataFlowDirection,
) : ApiBpmnDataAssociation

@JsonClassDescription("BPMN participant (pool): white-box owns a process, black-box is an external entity")
data class BpmnParticipant(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable participant id, e.g. Participant_OrderSvc")
    override val id: String,
    @get:JsonPropertyDescription("Pool label; for a white-box pool this is the process name")
    override val name: String? = null,
    @get:JsonPropertyDescription("Id of the process this pool owns; LEAVE NULL for a black-box (external) participant")
    override val processRef: String? = null,
) : ApiBpmnParticipant

@JsonClassDescription("BPMN lane: partitions a white-box pool by business role or performer")
data class BpmnLane(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable lane id, e.g. Lane_Sales")
    override val id: String,
    @get:JsonPropertyDescription("Lane label — a business role or performer, e.g. \"Sales\"")
    override val name: String? = null,
    @get:JsonPropertyDescription(
        "Id of the participant (pool) this lane belongs to; null when the process has lanes but no surrounding collaboration",
    )
    override val participantId: String? = null,
    @get:JsonPropertyDescription("Ids of the flow nodes contained in this lane (node ids, not names)")
    override val flowNodeRefs: List<String> = emptyList(),
) : ApiBpmnLane

@JsonClassDescription("BPMN message flow between two participants (across pools only)")
data class BpmnMessageFlow(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable message-flow id, e.g. MessageFlow_1")
    override val id: String,
    @get:JsonPropertyDescription("Optional label, e.g. \"Payment request\"")
    override val name: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription("Source: a flow-element id or a black-box participant id")
    override val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target: a flow-element id or a black-box participant id")
    override val targetRef: String,
) : ApiBpmnMessageFlow
