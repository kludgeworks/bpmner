/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Annotation-free sealed BPMN domain hierarchy. Every concrete BPMN data class in
 * `core/BpmnDomain.kt` implements one of these interfaces. Rule engines (compiled and
 * Pkl-authored) consume the api types so they never depend on Jackson, Jakarta Validation,
 * or any framework-side detail of the core types.
 *
 * The hierarchy is sealed all the way down, which gives api-side `when` blocks compile-time
 * exhaustiveness: every new BPMN subtype must be added to both api and core in a single
 * change. The cost is that api+core must stay in one Kotlin compilation module — if api/
 * is ever split out as a standalone JAR, the seal must be lifted first.
 */
interface BpmnDefinition {
    val processId: String
    val processName: String
    val nodes: List<BpmnNode>
    val sequences: List<BpmnEdge>
    val messages: List<BpmnMessageRef>
    val signals: List<BpmnSignalRef>
    val errors: List<BpmnErrorRef>
    val escalations: List<BpmnEscalationRef>

    /** Free-text annotations attached to elements via [associations]. */
    val annotations: List<BpmnTextAnnotation> get() = emptyList()

    /** Visual grouping artifacts. Groups carry no control-flow, data-flow, or ownership semantics. */
    val groups: List<BpmnGroup> get() = emptyList()

    /** Association edges linking annotations (and other artifacts) to flow elements. */
    val associations: List<BpmnAssociation> get() = emptyList()

    /** Data objects (transient information) flowing through the process. */
    val dataObjects: List<BpmnDataObject> get() = emptyList()

    /** Data stores (persisted information: databases, files, queues) the process reads or writes. */
    val dataStores: List<BpmnDataStore> get() = emptyList()

    /** Read/write links between an activity and a data object or store. */
    val dataAssociations: List<BpmnDataAssociation> get() = emptyList()

    /**
     * Participants (pools) in the collaboration. A white-box participant owns this process
     * (`processRef` set); a black-box participant is an external entity (`processRef` null).
     * Empty for an ordinary single-pool-less process — the renderer emits a `<bpmn:collaboration>`
     * only when this is non-empty.
     */
    val participants: List<BpmnParticipant> get() = emptyList()

    /**
     * Lanes partitioning a white-box pool by business role/performer. Lane membership is carried
     * solely by [BpmnLane.flowNodeRefs] — nodes do not back-reference their lane.
     */
    val lanes: List<BpmnLane> get() = emptyList()

    /** Message flows between participants. Distinct from [sequences]: they cross pool boundaries. */
    val messageFlows: List<BpmnMessageFlow> get() = emptyList()

    // Count of `<bpmndi:BPMNDiagram>` elements observed in the parsed XML. The semantic
    // model does not carry DI content; the count is the only signal that survives. The
    // `NoDuplicateDiagrams` rule reads this via synthetic `bpmndi:BPMNDiagram` elements
    // injected by `PrimitiveModelMapping`.
    val diagramCount: Int get() = 0
}

/**
 * A BPMN node. Implementations live in `core/BpmnDomain.kt`; each carries the Jackson
 * polymorphism annotations and Jakarta Validation constraints required for LLM JSON
 * round-tripping.
 *
 * [withName] is the api-side replacement for the prior `core.BpmnNode.withName` extension
 * function. Sealed interfaces have no synthetic `copy`, so each concrete data class
 * overrides this method, returning a clone of its own subtype with [name] replaced.
 */
interface BpmnNode {
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

/** Grouping marker for activity-position nodes; supports `BpmnNode.isTask()` dispatch. */
sealed interface BpmnTask : BpmnNode {
    /**
     * Multi-instance loop characteristics, or `null` for an ordinary single-run activity.
     * Present when the activity runs once per item in a collection. Cross-cutting across all
     * task kinds; events and gateways never carry it. Declared here so callers and the rule
     * engine read it polymorphically over any task without an exhaustive `when`.
     */
    val multiInstance: MultiInstanceLoopCharacteristics?

    /**
     * Standard-loop characteristics, or `null` for an ordinary single-run activity. Present when
     * the activity repeats until a condition is met (a while/until/retry loop). Cross-cutting
     * across all task kinds, declared here for polymorphic reads; distinct from [multiInstance]
     * (which runs once per item in a collection rather than repeating until a condition holds).
     */
    val standardLoop: StandardLoopCharacteristics?
}

/**
 * Multi-instance loop characteristics attached to a [BpmnTask]. Annotation-free api view;
 * the concrete data class (with Jackson / Jakarta annotations) lives in `core/BpmnDomain.kt`
 * and renders to `<bpmn:multiInstanceLoopCharacteristics>` on the task element.
 */
interface MultiInstanceLoopCharacteristics {
    val mode: MultiInstanceMode
    val collectionDescription: String
    val loopCardinality: Int?
    val completionCondition: String?
}

/**
 * Standard-loop characteristics attached to a [BpmnTask]. Annotation-free api view; the concrete
 * data class lives in `core/BpmnDomain.kt` and renders to `<bpmn:standardLoopCharacteristics>` on
 * the task element.
 */
interface StandardLoopCharacteristics {
    val testBefore: Boolean
    val loopCondition: String?
    val loopMaximum: Int?
}

/** Grouping marker for gateway nodes. */
sealed interface BpmnGateway : BpmnNode

/** Grouping marker for event-position nodes carrying an event definition. */
sealed interface BpmnEvent : BpmnNode {
    val eventDefinition: BpmnEventDefinition
}

// Leaf interfaces are intentionally non-sealed: Kotlin requires direct subtypes of a sealed
// interface to live in the same package, so sealing the leaves would block the core data
// classes (in `dev.groknull.bpmner.core`) from implementing them. Exhaustive `when` over
// `BpmnNode` still works because the sealed parents (`BpmnNode`, `BpmnEvent`, `BpmnTask`,
// `BpmnGateway`) constrain their direct subtypes — all 14 leaves are declared here.

interface BpmnStartEvent : BpmnEvent {
    val isInterrupting: Boolean
}

interface BpmnEndEvent : BpmnEvent

interface BpmnIntermediateCatchEvent : BpmnEvent

interface BpmnIntermediateThrowEvent : BpmnEvent

interface BpmnBoundaryEvent : BpmnEvent {
    val attachedToRef: String
    val cancelActivity: Boolean
}

interface BpmnUserTask : BpmnTask

interface BpmnServiceTask : BpmnTask

interface BpmnScriptTask : BpmnTask

interface BpmnBusinessRuleTask : BpmnTask {
    val decisionRef: String
}

interface BpmnSendTask : BpmnTask {
    val messageRef: String
}

interface BpmnReceiveTask : BpmnTask {
    val messageRef: String
}

interface BpmnManualTask : BpmnTask

interface BpmnExclusiveGateway : BpmnGateway

interface BpmnInclusiveGateway : BpmnGateway

interface BpmnParallelGateway : BpmnGateway

interface BpmnEventBasedGateway : BpmnGateway

/**
 * An embedded subprocess — a composite activity that contains its own start-to-end flow.
 * Modelled as a flat marker node: its inner nodes/edges live in the same [BpmnDefinition.nodes] /
 * [BpmnDefinition.sequences] lists carrying [BpmnNode.parentRef] = this subprocess's id; the renderer
 * reconstructs the `<bpmn:subProcess>` nesting. [triggeredByEvent] distinguishes an ordinary
 * subprocess (false) from an event subprocess (true).
 */
interface BpmnSubProcess : BpmnNode {
    val triggeredByEvent: Boolean
}

/**
 * A call activity — a single composite step that delegates to another, separately-defined process
 * referenced by id ([calledElement]), rather than containing inline flow. Renders to
 * `<bpmn:callActivity calledElement="…">`. The called process is resolved externally (another file
 * or the runtime catalogue); a [calledElement] that names no process in *this* definition is the
 * normal cross-file case, not an error.
 */
interface BpmnCallActivity : BpmnNode {
    val calledElement: String
}

/**
 * Fallback for any process element the parser sees but doesn't have a typed Kotlin class for
 * (e.g. `bpmn:Choreography`, `bpmn:Transaction`). The rule engine sees and flags these via
 * `targetElements` matching on [bpmnType].
 *
 * Not round-trippable: the generator (`BpmnDefinitionToXmlConverter` /
 * `BpmnModelFactory.newFlowNode`) errors if one reaches it. Callers serializing to LLM JSON
 * also fail because `BpmnUnrecognizedNode` is intentionally absent from `@JsonSubTypes`.
 */
interface BpmnUnrecognizedNode : BpmnNode {
    val bpmnType: String
}

/** A directed sequence-flow edge between two nodes. */
interface BpmnEdge {
    val id: String
    val sourceRef: String
    val targetRef: String
    val name: String?
    val conditionExpression: String?
    val isDefault: Boolean

    /**
     * Id of the [BpmnSubProcess] this edge is nested inside, or `null` for a top-level edge. BPMN
     * forbids sequence flows crossing a subprocess boundary, so an edge sits wholly in one scope.
     */
    val parentRef: String?
}

/**
 * A BPMN text annotation: free-text commentary on the diagram. Carries no flow semantics;
 * linked to the element it explains by a [BpmnAssociation]. Renders to `<bpmn:textAnnotation>`.
 */
interface BpmnTextAnnotation {
    val id: String
    val text: String
}

/** A BPMN group artifact: a visual, non-semantic container rendered as `<bpmn:group>`. */
interface BpmnGroup {
    val id: String
    val name: String?
}

/**
 * A BPMN association edge linking the flow element it annotates (the source) to a
 * [BpmnTextAnnotation] (the target). Per BPMN convention `sourceRef` is the annotated element
 * and `targetRef` is the annotation. Distinct from [BpmnEdge] (sequence flow): associations
 * carry no token flow. Renders to `<bpmn:association>`.
 */
interface BpmnAssociation {
    val id: String
    val sourceRef: String
    val targetRef: String
}

/**
 * A BPMN participant: a pool in a collaboration. White-box (`processRef` set) owns a process and
 * its lanes/nodes are visible; black-box (`processRef` null) is an opaque external entity. Renders
 * to `<bpmn:participant>` inside a `<bpmn:collaboration>`.
 */
interface BpmnParticipant {
    val id: String
    val name: String?
    val processRef: String?
}

/**
 * A BPMN lane: a horizontal partition of a pool by business role or performer. Membership is the
 * lane's [flowNodeRefs] (the sole source of truth — nodes carry no lane back-reference). Renders to
 * `<bpmn:lane>` (with `<bpmn:flowNodeRef>` children) inside the process's `<bpmn:laneSet>`.
 *
 * [participantId] is the owning pool, or `null` when the process carries a lane set without a
 * surrounding collaboration (a single-process diagram with lanes but no explicit pool).
 */
interface BpmnLane {
    val id: String
    val name: String?
    val participantId: String?
    val flowNodeRefs: List<String>
}

/**
 * A BPMN message flow: an async message between two participants. Unlike [BpmnEdge] (sequence flow,
 * within one pool) a message flow crosses pool boundaries; `sourceRef`/`targetRef` may be a flow
 * element id or a black-box participant id. Renders to `<bpmn:messageFlow>` in the collaboration.
 */
interface BpmnMessageFlow {
    val id: String
    val name: String?
    val sourceRef: String
    val targetRef: String
}

/** A BPMN data object: transient information flowing through the process. Renders to `<bpmn:dataObject>`. */
interface BpmnDataObject {
    val id: String
    val name: String
}

/**
 * A BPMN data store: persisted information (database, file, queue) the process reads or writes.
 * Renders to `<bpmn:dataStore>`.
 */
interface BpmnDataStore {
    val id: String
    val name: String
}

/** Direction of a [BpmnDataAssociation]: the activity reads from, or writes to, the data element. */
enum class DataFlowDirection { READ, WRITE }

/**
 * A read/write link between an activity (`sourceRef`) and a data object or store (`targetRef`).
 * Distinct from [BpmnAssociation] (annotation links) and [BpmnEdge] (token flow). Renders to a
 * `<bpmn:dataInputAssociation>` (READ) or `<bpmn:dataOutputAssociation>` (WRITE) child of the activity.
 */
interface BpmnDataAssociation {
    val id: String
    val sourceRef: String
    val targetRef: String
    val direction: DataFlowDirection
}

/** Process-level message catalog entry, referenced by message event definitions and tasks. */
interface BpmnMessageRef {
    val id: String
    val name: String
}

/** Process-level signal catalog entry. */
interface BpmnSignalRef {
    val id: String
    val name: String
}

/** Process-level error catalog entry. */
interface BpmnErrorRef {
    val id: String
    val code: String
    val name: String?
}

/** Process-level escalation catalog entry. */
interface BpmnEscalationRef {
    val id: String
    val code: String
    val name: String?
}

/**
 * Event-definition hierarchy carried by `BpmnEvent` subtypes. The top is intentionally
 * non-sealed so concrete `core` data classes (in a different package) can implement it
 * directly; the 7 leaf interfaces below are also non-sealed for the same reason. The
 * package-locality rule that applies to `sealed` interfaces in Kotlin would otherwise
 * block the cross-package implementation.
 */
interface BpmnEventDefinition

interface BpmnNoneEventDefinition : BpmnEventDefinition

interface BpmnTimerEventDefinition : BpmnEventDefinition {
    val timerKind: BpmnTimerKind
    val expression: String
}

interface BpmnMessageEventDefinition : BpmnEventDefinition {
    val messageRef: String
}

interface BpmnSignalEventDefinition : BpmnEventDefinition {
    val signalRef: String
}

interface BpmnErrorEventDefinition : BpmnEventDefinition {
    val errorRef: String
}

interface BpmnEscalationEventDefinition : BpmnEventDefinition {
    val escalationRef: String
}

interface BpmnTerminateEventDefinition : BpmnEventDefinition

/**
 * Fallback for any event definition the parser sees but doesn't have a typed Kotlin class for
 * (e.g. `bpmn:CompensateEventDefinition`). Carries the source XML typename so the rule engine
 * can flag specific definitions via `targetElements`.
 */
interface BpmnUnrecognizedEventDefinition : BpmnEventDefinition {
    val typeName: String
}

/**
 * The discriminator string for [this] node, matching the `@JsonSubTypes` names on the
 * concrete `core` data classes. Kept as a property extension to preserve the existing
 * `node.typeName` call-site syntax across the codebase.
 *
 * NOTE: the string literals here must stay in sync with the `name` values in the
 * `@JsonSubTypes` annotation on `core.BpmnNode`. The exhaustive `when` catches missing
 * arms when a new subtype is added but cannot catch a typo or divergence; if a subtype is
 * renamed, update both lists together.
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
            is BpmnInclusiveGateway -> "INCLUSIVE_GATEWAY"
            is BpmnParallelGateway -> "PARALLEL_GATEWAY"
            is BpmnEventBasedGateway -> "EVENT_BASED_GATEWAY"
            is BpmnIntermediateCatchEvent -> "INTERMEDIATE_CATCH_EVENT"
            is BpmnIntermediateThrowEvent -> "INTERMEDIATE_THROW_EVENT"
            is BpmnBoundaryEvent -> "BOUNDARY_EVENT"
            is BpmnEndEvent -> "END_EVENT"
            is BpmnSubProcess -> "SUB_PROCESS"
            is BpmnCallActivity -> "CALL_ACTIVITY"
            is BpmnUnrecognizedNode -> "UNRECOGNIZED:$bpmnType"
            else -> error("Unknown BpmnNode subtype: ${this::class.qualifiedName}")
        }

/**
 * True when [this] is one of the BPMN task subtypes. Backed by the marker interface
 * [BpmnTask], so every new task subtype that extends [BpmnTask] participates automatically.
 */
fun BpmnNode.isTask(): Boolean = this is BpmnTask
