/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.groknull.bpmner.api.BoundaryEventKind
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.MultiInstanceMode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@JsonClassDescription("Source-grounded process start declaration")
data class ContractStart(
    @field:Valid
    @get:JsonPropertyDescription("Typed start trigger semantics")
    val trigger: ContractTrigger,
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Source ids grounding the trigger in source evidence")
    val sourceIds: List<String> = emptyList(),
)

@JsonClassDescription("Typed process trigger")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ContractTrigger.None::class, name = "NONE"),
    JsonSubTypes.Type(value = ContractTrigger.Timer::class, name = "TIMER"),
    JsonSubTypes.Type(value = ContractTrigger.Message::class, name = "MESSAGE"),
    JsonSubTypes.Type(value = ContractTrigger.Signal::class, name = "SIGNAL"),
)
sealed interface ContractTrigger {
    val description: String

    data class None(
        override val description: String,
    ) : ContractTrigger

    data class Timer(
        val timerKind: BpmnTimerKind,
        val expression: String,
        override val description: String,
    ) : ContractTrigger

    data class Message(
        val messageName: String,
        override val description: String,
    ) : ContractTrigger

    data class Signal(
        val signalName: String,
        override val description: String,
    ) : ContractTrigger
}

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
    @field:Valid
    @get:JsonPropertyDescription("Typed process start derived from the source input")
    val start: ContractStart,
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
    @get:JsonPropertyDescription("Intermediate throw events required in the middle of the process")
    val intermediateThrows: List<ContractIntermediateThrow> = emptyList(),
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Assumptions made while extracting the contract")
    val assumptions: List<ContractAssumption> = emptyList(),
) {
    // Backward-compat read-only views over `start` for existing Kotlin callers that still use
    // the flat `trigger: String` / `triggerSourceIds: List<String>` shape via the secondary
    // constructor below. JsonIgnore keeps them out of the JSON wire format — without this,
    // Jackson serialises `trigger` as a duplicate top-level property and round-trip
    // deserialisation fails with "Unrecognized field 'trigger'".
    @get:JsonIgnore
    val trigger: String
        get() = start.trigger.description

    @get:JsonIgnore
    val triggerSourceIds: List<String>
        get() = start.sourceIds

    constructor(
        id: String,
        processName: String,
        summary: String,
        trigger: String,
        triggerSourceIds: List<String> = emptyList(),
        activities: List<ContractActivity>,
        decisions: List<ContractDecision> = emptyList(),
        actors: List<ContractActor> = emptyList(),
        artifacts: List<ContractArtifact> = emptyList(),
        endStates: List<ContractEndState>,
        intermediateThrows: List<ContractIntermediateThrow> = emptyList(),
        assumptions: List<ContractAssumption> = emptyList(),
    ) : this(
        id = id,
        processName = processName,
        summary = summary,
        start = ContractStart(ContractTrigger.None(trigger), triggerSourceIds),
        activities = activities,
        decisions = decisions,
        actors = actors,
        artifacts = artifacts,
        endStates = endStates,
        intermediateThrows = intermediateThrows,
        assumptions = assumptions,
    )
}

/**
 * Activity required by the extracted process contract.
 *
 * Mirrors the sealed-subtype pattern used by [ContractTrigger] and [ContractBranch]:
 * the `kind` discriminator dispatches to one of seven subtypes,
 * each carrying exactly the fields its task kind needs. Kind / payload coupling is
 * enforced by the type system — `Send`/`Receive` carry `messageName`, `BusinessRule`
 * carries `decisionName`, others carry nothing kind-specific.
 *
 * Subtypes map 1:1 to BPMN task kinds in `dev.groknull.bpmner.core`:
 *
 *  - [Service] — external/system automation → `BpmnServiceTask`
 *  - [User] — human work through a system UI → `BpmnUserTask`
 *  - [Script] — engine-evaluated computation, no external service → `BpmnScriptTask`
 *  - [BusinessRule] — rule-set / decision-table evaluation → `BpmnBusinessRuleTask`
 *  - [Send] — fire-and-forget outbound message → `BpmnSendTask`
 *  - [Receive] — wait for an inbound message → `BpmnReceiveTask`
 *  - [Manual] — human work without system support → `BpmnManualTask`
 *
 * The companion object's `invoke` keeps the old flat-constructor call sites working
 * (`ContractActivity("id", "name")`) by defaulting to [Service] — the most common kind.
 */
@JsonClassDescription("Activity required by the extracted process contract")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ContractActivity.Service::class, name = "SERVICE"),
    JsonSubTypes.Type(value = ContractActivity.User::class, name = "USER"),
    JsonSubTypes.Type(value = ContractActivity.Script::class, name = "SCRIPT"),
    JsonSubTypes.Type(value = ContractActivity.BusinessRule::class, name = "BUSINESS_RULE"),
    JsonSubTypes.Type(value = ContractActivity.Send::class, name = "SEND"),
    JsonSubTypes.Type(value = ContractActivity.Receive::class, name = "RECEIVE"),
    JsonSubTypes.Type(value = ContractActivity.Manual::class, name = "MANUAL"),
)
sealed interface ContractActivity {
    val id: String
    val name: String
    val actorId: String?
    val sourceIds: List<String>

    /**
     * Cross-cutting modifiers (iteration, boundary events, loop, data associations) shared by every
     * activity kind. Grouped into one value object so the subtypes carry only their kind-specific
     * payload; the [iteration], [boundaryEvents], [loop], [dataInputIds], and [dataOutputIds]
     * extension accessors read them polymorphically without a `when` over the subtypes.
     */
    val modifiers: ActivityModifiers

    @JsonClassDescription("Service activity — external/system automation. Maps to BpmnServiceTask.")
    data class Service(
        override val id: String,
        override val name: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription("User activity — human work through a system UI. Maps to BpmnUserTask.")
    data class User(
        override val id: String,
        override val name: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription("Script activity — engine-evaluated computation, no external service. Maps to BpmnScriptTask.")
    data class Script(
        override val id: String,
        override val name: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription(
        "Business-rule activity — rule-set or decision-table evaluation. Maps to BpmnBusinessRuleTask.",
    )
    data class BusinessRule(
        override val id: String,
        override val name: String,
        val decisionName: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription("Send activity — fire-and-forget outbound message. Maps to BpmnSendTask.")
    data class Send(
        override val id: String,
        override val name: String,
        val messageName: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription(
        "Receive activity — blocks the flow until an inbound message arrives. Maps to BpmnReceiveTask.",
    )
    data class Receive(
        override val id: String,
        override val name: String,
        val messageName: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    @JsonClassDescription("Manual activity — human work without system support. Maps to BpmnManualTask.")
    data class Manual(
        override val id: String,
        override val name: String,
        override val actorId: String? = null,
        override val sourceIds: List<String> = emptyList(),
        override val modifiers: ActivityModifiers = ActivityModifiers(),
    ) : ContractActivity

    companion object {
        // Convenience factory: lets existing call sites that don't specify a kind keep working
        // (`ContractActivity("id", "name")` → Service). Most contract activities are SERVICE in
        // practice, so this is a useful default rather than a backward-compat hack.
        operator fun invoke(
            id: String,
            name: String,
            actorId: String? = null,
            sourceIds: List<String> = emptyList(),
        ): ContractActivity = Service(id = id, name = name, actorId = actorId, sourceIds = sourceIds)
    }
}

/**
 * Cross-cutting modifiers shared by every [ContractActivity] kind: a per-item [iteration] marker,
 * attached [boundaryEvents], a standard [loop] marker, and the artifact ids the activity reads
 * ([dataInputIds]) / writes ([dataOutputIds]). The defaults describe an ordinary single-run
 * activity that touches no declared data.
 */
data class ActivityModifiers(
    val iteration: ContractIteration? = null,
    val boundaryEvents: List<ContractBoundaryEvent> = emptyList(),
    val loop: ContractLoop? = null,
    val dataInputIds: List<String> = emptyList(),
    val dataOutputIds: List<String> = emptyList(),
)

/** Per-item iteration marker, read polymorphically across activity kinds via [ContractActivity.modifiers]. */
val ContractActivity.iteration: ContractIteration? get() = modifiers.iteration

/** Boundary events attached to the activity, read polymorphically via [ContractActivity.modifiers]. */
val ContractActivity.boundaryEvents: List<ContractBoundaryEvent> get() = modifiers.boundaryEvents

/** Standard-loop marker, read polymorphically via [ContractActivity.modifiers]. */
val ContractActivity.loop: ContractLoop? get() = modifiers.loop

/** Ids of artifacts the activity reads, read polymorphically via [ContractActivity.modifiers]. */
val ContractActivity.dataInputIds: List<String> get() = modifiers.dataInputIds

/** Ids of artifacts the activity writes, read polymorphically via [ContractActivity.modifiers]. */
val ContractActivity.dataOutputIds: List<String> get() = modifiers.dataOutputIds

@JsonClassDescription(
    "Per-item iteration over a collection (multi-instance): the activity runs once per item, " +
        "either one at a time (SEQUENTIAL) or concurrently (PARALLEL).",
)
data class ContractIteration(
    @get:JsonPropertyDescription(
        "SEQUENTIAL = items handled one at a time / in order; PARALLEL = items handled concurrently.",
    )
    val mode: MultiInstanceMode,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription(
        "Human-readable description of the collection iterated over, e.g. \"each reviewer on the panel\".",
    )
    val collectionDescription: String,
    @get:JsonPropertyDescription("Optional fixed iteration count when the source states a fixed number")
    val loopCardinality: Int? = null,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Optional early-exit condition that stops iteration before all items are done")
    val completionCondition: String? = null,
)

@JsonClassDescription(
    "A boundary event attached to an activity: a timeout (TIMER), caught business error (ERROR), " +
        "or raised escalation (ESCALATION) that interrupts the activity and routes the flow to " +
        "`nextRef`. Realized as a `BpmnBoundaryEvent` on the activity's BPMN task.",
)
data class ContractBoundaryEvent(
    @get:JsonPropertyDescription(
        "Event kind: TIMER (a deadline/duration elapses), ERROR (the activity throws a named " +
            "business error), ESCALATION (the activity raises a business escalation).",
    )
    val kind: BoundaryEventKind,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Short label for the event, e.g. \"24h timeout\" or \"chargeback raised\".")
    val label: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Id of the activity, decision, or end state the exception path routes to when this event fires.",
    )
    val nextRef: String,
    @get:JsonPropertyDescription(
        "Whether firing interrupts (cancels) the attached activity. Default true. ERROR boundary " +
            "events must be interrupting; TIMER/ESCALATION may be non-interrupting.",
    )
    val cancelActivity: Boolean = true,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Optional kind-specific detail: an ISO-8601 duration for TIMER (e.g. \"PT24H\"), a business " +
            "error code for ERROR (e.g. \"CHARGEBACK\"), or an escalation code for ESCALATION.",
    )
    val detail: String? = null,
)

@JsonClassDescription(
    "Standard loop over a single activity that repeats until a condition is met (while/until/retry).",
)
data class ContractLoop(
    @get:JsonPropertyDescription(
        "true = while-loop (the condition is checked before each run); false = until-loop (the " +
            "activity runs once, then the condition is checked after).",
    )
    val testBefore: Boolean = true,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Human-readable loop continue/exit condition, e.g. \"payment not yet successful\".")
    val loopCondition: String? = null,
    @get:JsonPropertyDescription("Optional cap on the number of iterations, e.g. retry up to 3 times.")
    val loopMaximum: Int? = null,
)

/**
 * The discriminator string for [activity], matching the `kind` field in its serialized JSON
 * and the names declared in `@JsonSubTypes` on [ContractActivity].
 */
val ContractActivity.kindName: String
    get() =
        when (this) {
            is ContractActivity.Service -> "SERVICE"
            is ContractActivity.User -> "USER"
            is ContractActivity.Script -> "SCRIPT"
            is ContractActivity.BusinessRule -> "BUSINESS_RULE"
            is ContractActivity.Send -> "SEND"
            is ContractActivity.Receive -> "RECEIVE"
            is ContractActivity.Manual -> "MANUAL"
        }

/**
 * Returns a new [ContractActivity] of the same concrete subtype with [sourceIds] replaced.
 * Sealed interfaces have no synthetic `copy`, so this helper dispatches across the subtypes
 * exhaustively. Used by callers that need to mutate provenance without committing to a
 * specific subtype (e.g. validation tests).
 */
fun ContractActivity.withSourceIds(sourceIds: List<String>): ContractActivity = when (this) {
    is ContractActivity.Service -> copy(sourceIds = sourceIds)
    is ContractActivity.User -> copy(sourceIds = sourceIds)
    is ContractActivity.Script -> copy(sourceIds = sourceIds)
    is ContractActivity.BusinessRule -> copy(sourceIds = sourceIds)
    is ContractActivity.Send -> copy(sourceIds = sourceIds)
    is ContractActivity.Receive -> copy(sourceIds = sourceIds)
    is ContractActivity.Manual -> copy(sourceIds = sourceIds)
}

/**
 * How the branches of a [ContractDecision] are selected at runtime.
 *
 * Defaults to [EXCLUSIVE] so existing contracts (exclusive choice — exactly one branch
 * taken) need no schema change.
 */
enum class ContractGatewayKind {
    /**
     * Exactly one branch is taken based on its condition. Maps to `<bpmn:exclusiveGateway>`.
     * Branches under EXCLUSIVE decisions normally carry a `condition` expression.
     */
    EXCLUSIVE,

    /**
     * One or more branches activate based on their conditions — some-but-not-all of the
     * conditional concurrent paths fire. Branches under INCLUSIVE decisions carry
     * `condition` expressions like EXCLUSIVE, but unlike EXCLUSIVE the conditions are
     * evaluated independently and any subset (including all of them) may evaluate true
     * and fire concurrently. A matching inclusive join gateway waits for whichever
     * branches activated before downstream work proceeds. Maps to `<bpmn:inclusiveGateway>`.
     */
    INCLUSIVE,

    /**
     * All branches activate concurrently — a fork. Branches under PARALLEL decisions have
     * no `condition`; the gateway emits one outbound flow per branch unconditionally, and
     * a matching parallel join gateway synchronises the branches before downstream work
     * proceeds. Maps to `<bpmn:parallelGateway>`.
     */
    PARALLEL,

    /**
     * The flow waits for one of several events; the first to fire selects its branch. Maps to
     * `<bpmn:eventBasedGateway>`. Branches are [EventGatewayBranch] — each names the event
     * (TIMER / MESSAGE / SIGNAL) that triggers it rather than carrying a condition.
     */
    EVENT_BASED,
}

@JsonClassDescription("Decision required by the extracted process contract")
data class ContractDecision(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable decision id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription(
        "Decision question from the workflow. For PARALLEL decisions this still names the " +
            "split (e.g. 'Run all preparation tracks') even though there is no conditional choice.",
    )
    val question: String,
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Branches that can be taken from this decision")
    val branches: List<ContractBranch>,
    @get:JsonPropertyDescription(
        "How the branches relate. EXCLUSIVE (default) = exactly one branch taken based on its " +
            "condition. INCLUSIVE = one or more branches whose conditions are true activate " +
            "concurrently and reconverge at a join — use when the source describes independent " +
            "optional add-ons that may apply singly, together, or not at all ('either, both, " +
            "or neither'). PARALLEL = all branches activate concurrently and reconverge at a join. " +
            "Use PARALLEL when the source describes concurrent / independent tracks, 'in parallel', " +
            "'all of the following must complete', etc.",
    )
    val kind: ContractGatewayKind = ContractGatewayKind.EXCLUSIVE,
    @field:Size(max = 10)
    @get:JsonPropertyDescription("Source ids grounding this decision in evidence.")
    val sourceIds: List<String> = emptyList(),
)

/**
 * A branch out of a [ContractDecision].
 *
 * Mirrors the sealed-subtype pattern used by [dev.groknull.bpmner.core.BpmnNode]:
 * the `kind` discriminator dispatches to one of three subtypes, each
 * carrying exactly the fields it needs. Mutual exclusion between `condition` and "default" is a
 * type-system guarantee — there is no shape where both could coexist.
 *
 * - [ConditionalBranch] — taken when its `condition` evaluates true. The default kind for
 *   EXCLUSIVE decisions; never appears on PARALLEL decisions.
 * - [DefaultBranch] — the catch-all on an EXCLUSIVE (or, later, INCLUSIVE) decision. Taken when
 *   no other branch's condition matched. Carries no condition; renders as `default="Flow_X"` on
 *   the gateway.
 * - [UnconditionalBranch] — a branch of a PARALLEL decision. Activates unconditionally; the
 *   matching parallel join synchronises the tracks downstream.
 */
@JsonClassDescription("Branch out of a contract decision")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ConditionalBranch::class, name = "CONDITIONAL"),
    JsonSubTypes.Type(value = DefaultBranch::class, name = "DEFAULT"),
    JsonSubTypes.Type(value = UnconditionalBranch::class, name = "UNCONDITIONAL"),
    JsonSubTypes.Type(value = EventGatewayBranch::class, name = "EVENT_GATEWAY"),
)
sealed interface ContractBranch {
    val id: String
    val label: String
    val nextRef: String?
}

/**
 * The discriminator string for [branch], matching the `kind` field in its serialized JSON
 * and the names declared in `@JsonSubTypes` on [ContractBranch].
 */
val ContractBranch.kindName: String
    get() =
        when (this) {
            is ConditionalBranch -> "CONDITIONAL"
            is DefaultBranch -> "DEFAULT"
            is UnconditionalBranch -> "UNCONDITIONAL"
            is EventGatewayBranch -> "EVENT_GATEWAY"
        }

@JsonClassDescription("Conditional branch — taken when `condition` evaluates true")
data class ConditionalBranch(
    override val id: String,
    override val label: String,
    val condition: String,
    override val nextRef: String? = null,
) : ContractBranch

@JsonClassDescription(
    "Default (catch-all) branch — taken when no other branch's condition matched. " +
        "Valid on EXCLUSIVE decisions only. At most one per decision.",
)
data class DefaultBranch(
    override val id: String,
    override val label: String,
    override val nextRef: String? = null,
) : ContractBranch

@JsonClassDescription(
    "Unconditional branch of a PARALLEL decision — fires concurrently with its siblings; " +
        "the matching parallel join synchronises the tracks downstream.",
)
data class UnconditionalBranch(
    override val id: String,
    override val label: String,
    override val nextRef: String? = null,
) : ContractBranch

/**
 * The event that selects an [EventGatewayBranch] of an EVENT_BASED decision: a deadline elapsing
 * (`TIMER`), a named message arriving (`MESSAGE`), or a broadcast being observed (`SIGNAL`).
 * Realized as the intermediate catch event the event-based gateway routes to on that branch.
 */
enum class EventTriggerKind {
    TIMER,
    MESSAGE,
    SIGNAL,
}

@JsonClassDescription(
    "Branch of an EVENT_BASED decision — taken when its event fires first. Names the triggering " +
        "event kind (TIMER / MESSAGE / SIGNAL) instead of a condition.",
)
data class EventGatewayBranch(
    override val id: String,
    override val label: String,
    val triggerKind: EventTriggerKind,
    val triggerDetail: String,
    override val nextRef: String? = null,
) : ContractBranch

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

enum class ContractArtifactKind { DATA_OBJECT, DATA_STORE }

@JsonClassDescription(
    "Data artifact referenced by the contract: a DATA_OBJECT (transient information flowing through " +
        "the process) or a DATA_STORE (persisted store — database, file, queue).",
)
data class ContractArtifact(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable artifact id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Business name of the artifact, e.g. \"Order\" or \"Customer database\"")
    val name: String,
    @field:NotNull
    @get:JsonPropertyDescription(
        "DATA_OBJECT = transient information flowing through the process; DATA_STORE = persisted " +
            "information (database, file, queue) the process reads or writes.",
    )
    val kind: ContractArtifactKind,
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Optional artifact description")
    val description: String? = null,
)

/**
 * Required end state for the extracted process contract.
 *
 * Mirrors the sealed-subtype pattern used by [ContractTrigger], [ContractBranch],
 * and [ContractActivity]: the `kind` discriminator dispatches
 * to one of six subtypes, each carrying exactly the payload its end-event kind needs.
 * Kind / payload coupling is enforced by the type system — [Error] always carries an
 * `errorCode`, [Message] always carries a `messageName`, etc.
 *
 * Subtypes map 1:1 to BPMN end-event flavours rendered as
 * `<bpmn:endEvent>` with a matching [dev.groknull.bpmner.core.BpmnEventDefinition] child:
 *
 *  - [Normal] — vanilla path completion → [dev.groknull.bpmner.core.BpmnNoneEventDefinition]
 *  - [Terminate] — terminates the enclosing scope, killing all parallel tokens →
 *    [dev.groknull.bpmner.core.BpmnTerminateEventDefinition]
 *  - [Error] — raises a named error that propagates to the nearest matching boundary
 *    catcher (falls back to scope completion if uncaught per BPMN spec) →
 *    [dev.groknull.bpmner.core.BpmnErrorEventDefinition] + matching `BpmnErrorRef`
 *  - [Message] — point-to-point send on completion →
 *    [dev.groknull.bpmner.core.BpmnMessageEventDefinition] + matching `BpmnMessageRef`
 *  - [Signal] — broadcast to every subscribing process →
 *    [dev.groknull.bpmner.core.BpmnSignalEventDefinition] + matching `BpmnSignalRef`
 *  - [Escalation] — non-error notification that propagates to an escalation catcher
 *    (per Camunda best practice: use for "report back" rather than "this failed") →
 *    [dev.groknull.bpmner.core.BpmnEscalationEventDefinition] + matching `BpmnEscalationRef`
 *
 * Field naming follows the convention from [ContractTrigger]: Message/Signal carry
 * human-readable **names** (extracted from prose; mapped to catalogue ids at generation
 * time), Error/Escalation carry **codes** (the BPMN-spec matching identifier on
 * `<bpmn:error errorCode="...">` / `<bpmn:escalation escalationCode="...">`).
 *
 * The companion `invoke` keeps existing flat-constructor call sites compiling by
 * defaulting to [Normal] — the most common end-state kind in practice.
 */
@JsonClassDescription("Required end state for the extracted process contract")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = ContractEndState.Normal::class, name = "NORMAL"),
    JsonSubTypes.Type(value = ContractEndState.Terminate::class, name = "TERMINATE"),
    JsonSubTypes.Type(value = ContractEndState.Error::class, name = "ERROR"),
    JsonSubTypes.Type(value = ContractEndState.Message::class, name = "MESSAGE"),
    JsonSubTypes.Type(value = ContractEndState.Signal::class, name = "SIGNAL"),
    JsonSubTypes.Type(value = ContractEndState.Escalation::class, name = "ESCALATION"),
)
sealed interface ContractEndState {
    val id: String
    val name: String
    val sourceIds: List<String>

    @JsonClassDescription("Normal end — vanilla path completion. Maps to BpmnEndEvent with NoneEventDefinition.")
    data class Normal(
        override val id: String,
        override val name: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    @JsonClassDescription(
        "Terminate end — terminates the enclosing scope, killing all in-flight parallel " +
            "tokens. Maps to BpmnEndEvent with TerminateEventDefinition.",
    )
    data class Terminate(
        override val id: String,
        override val name: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    @JsonClassDescription(
        "Error end — raises a named error that propagates to the nearest matching boundary " +
            "catcher. Maps to BpmnEndEvent with ErrorEventDefinition.",
    )
    data class Error(
        override val id: String,
        override val name: String,
        val errorCode: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    @JsonClassDescription(
        "Message end — point-to-point message sent on completion. Maps to BpmnEndEvent with " +
            "MessageEventDefinition.",
    )
    data class Message(
        override val id: String,
        override val name: String,
        val messageName: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    @JsonClassDescription(
        "Signal end — broadcast to every subscribing process. Distinct from Message in being " +
            "one-to-many. Maps to BpmnEndEvent with SignalEventDefinition.",
    )
    data class Signal(
        override val id: String,
        override val name: String,
        val signalName: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    @JsonClassDescription(
        "Escalation end — non-error notification that propagates to an escalation catcher. " +
            "Distinct from Error: signals \"please nudge\" rather than \"this failed\". Maps to " +
            "BpmnEndEvent with EscalationEventDefinition.",
    )
    data class Escalation(
        override val id: String,
        override val name: String,
        val escalationCode: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractEndState

    companion object {
        // Convenience factory: lets existing call sites that don't specify a kind keep working
        // (`ContractEndState("id", "name")` → Normal). Most end states are NORMAL in practice;
        // matches the analogous `ContractActivity.invoke` companion.
        operator fun invoke(
            id: String,
            name: String,
            sourceIds: List<String> = emptyList(),
        ): ContractEndState = Normal(id = id, name = name, sourceIds = sourceIds)
    }
}

/**
 * The discriminator string for [endState], matching the `kind` field in the LLM JSON
 * output and the names declared in `@JsonSubTypes` on [ContractEndState].
 */
val ContractEndState.kindName: String
    get() =
        when (this) {
            is ContractEndState.Normal -> "NORMAL"
            is ContractEndState.Terminate -> "TERMINATE"
            is ContractEndState.Error -> "ERROR"
            is ContractEndState.Message -> "MESSAGE"
            is ContractEndState.Signal -> "SIGNAL"
            is ContractEndState.Escalation -> "ESCALATION"
        }

sealed interface ContractIntermediateThrow {
    val id: String
    val name: String
    val sourceIds: List<String>

    data class Message(
        override val id: String,
        override val name: String,
        val messageName: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractIntermediateThrow

    data class Signal(
        override val id: String,
        override val name: String,
        val signalName: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractIntermediateThrow

    data class Escalation(
        override val id: String,
        override val name: String,
        val escalationCode: String,
        override val sourceIds: List<String> = emptyList(),
    ) : ContractIntermediateThrow
}

val ContractIntermediateThrow.kindName: String
    get() =
        when (this) {
            is ContractIntermediateThrow.Message -> "MESSAGE"
            is ContractIntermediateThrow.Signal -> "SIGNAL"
            is ContractIntermediateThrow.Escalation -> "ESCALATION"
        }

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
