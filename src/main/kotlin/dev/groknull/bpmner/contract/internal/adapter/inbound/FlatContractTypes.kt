/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.api.BoundaryEventKind
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractArtifact
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.EventTriggerKind
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size

/*
 * Flat wire-format types that the LLM is asked to produce, deliberately not sealed.
 * Each former sealed hierarchy collapses to a single data class with an enum `kind`
 * discriminator and nullable kind-specific fields. This eliminates the `anyOf`
 * base-property repetition that Jackson/victools generates for sealed hierarchies
 * (see issue #296: ~83% of the prior schema was duplicate base properties).
 *
 * These types are an LLM-adapter implementation detail. Downstream packages keep
 * using the sealed ProcessContract from contract/BpmnContractTypes.kt; conversion
 * happens in FlatContractMapper.kt at the agent boundary.
 */

public enum class FlatActivityKind {
    SERVICE,
    USER,
    SCRIPT,
    BUSINESS_RULE,
    SEND,
    RECEIVE,
    MANUAL,
}

public enum class FlatEndStateKind {
    NORMAL,
    TERMINATE,
    ERROR,
    MESSAGE,
    SIGNAL,
    ESCALATION,
}

public enum class FlatIntermediateThrowKind {
    MESSAGE,
    SIGNAL,
    ESCALATION,
}

public enum class FlatBranchKind {
    CONDITIONAL,
    DEFAULT,
    UNCONDITIONAL,
    EVENT_GATEWAY,
}

public enum class FlatTriggerKind {
    NONE,
    TIMER,
    MESSAGE,
    SIGNAL,
}

@JsonClassDescription(
    "Activity required by the extracted process contract. Set `kind` and populate the matching " +
        "kind-specific field: BUSINESS_RULE → decisionName, SEND/RECEIVE → messageName. Other kinds " +
        "leave the kind-specific fields null.",
)
public data class FlatContractActivity(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(ACTIVITY_ID_DESCRIPTION)
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(ACTIVITY_NAME_DESCRIPTION)
    val name: String,
    @get:JsonPropertyDescription(
        "Activity kind. SERVICE (external/system automation, default), USER (human work via UI), " +
            "SCRIPT (engine-evaluated computation), BUSINESS_RULE (decision-table / rule-set; " +
            "populate decisionName), SEND (fire-and-forget outbound; populate messageName), " +
            "RECEIVE (block on inbound; populate messageName), MANUAL (human work without system support).",
    )
    val kind: FlatActivityKind,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(ACTIVITY_ACTOR_ID_DESCRIPTION)
    val actorId: String? = null,
    @field:Size(max = 10)
    @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
    val sourceIds: List<String> = emptyList(),
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=BUSINESS_RULE. Human-readable name of the decision / rule set the LLM " +
            "identified in the prose (e.g. \"credit policy\"). Downstream generator maps this to a " +
            "stable BpmnBusinessRuleTask.decisionRef id.",
    )
    val decisionName: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=SEND or RECEIVE. Human-readable name of the message (e.g. \"decline " +
            "notification\"). Downstream generator maps this to a stable BpmnMessageRef catalogue id.",
    )
    val messageName: String? = null,
    @field:Valid
    @get:JsonPropertyDescription(
        "Set when the activity runs once per item in a collection (\"for each …\"). Use SEQUENTIAL " +
            "when the source says items are handled one at a time / in order, PARALLEL when they run " +
            "concurrently / independently. Leave null for an ordinary single-run activity. This is a " +
            "per-item iteration marker, distinct from a retry/poll loop or a parallel gateway fork.",
    )
    val iteration: FlatContractIteration? = null,
    @field:Valid
    @get:JsonPropertyDescription(
        "Boundary events on this activity — timeouts, caught errors, or escalations that interrupt " +
            "it and route elsewhere (e.g. \"if approval takes longer than 24h, escalate\"; \"if the " +
            "payment subprocess raises a chargeback error, route to dispute handling\"). Leave empty " +
            "for ordinary activities. Distinct from a normal decision branch off the activity's outcome.",
    )
    val boundaryEvents: List<FlatContractBoundaryEvent> = emptyList(),
)

@JsonClassDescription(
    "Per-item iteration marker for an activity that repeats over a collection (multi-instance).",
)
public data class FlatContractIteration(
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
    "A boundary event on an activity: a timeout (TIMER), caught business error (ERROR), or raised " +
        "escalation (ESCALATION) that interrupts the activity and routes the flow to `nextRef`.",
)
public data class FlatContractBoundaryEvent(
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
    "Required end state. Set `kind` and populate the matching code/name field: ERROR → errorCode, " +
        "MESSAGE → messageName, SIGNAL → signalName, ESCALATION → escalationCode. NORMAL and " +
        "TERMINATE leave them null.",
)
public data class FlatContractEndState(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(END_STATE_ID_DESCRIPTION)
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(END_STATE_NAME_DESCRIPTION)
    val name: String,
    @get:JsonPropertyDescription(
        "End-state kind. NORMAL (vanilla completion, default), TERMINATE (kills all parallel " +
            "tokens), ERROR (propagates to boundary catcher; populate errorCode), MESSAGE " +
            "(point-to-point send on completion; populate messageName), SIGNAL (broadcast; populate " +
            "signalName), ESCALATION (notification, not failure; populate escalationCode).",
    )
    val kind: FlatEndStateKind,
    @field:Size(max = 10)
    @get:JsonPropertyDescription(END_STATE_SOURCE_IDS_DESCRIPTION)
    val sourceIds: List<String> = emptyList(),
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=ERROR. Stable business error code that boundary catchers match " +
            "(e.g. \"CREDIT_REJECTED\"). NOT a user-facing message.",
    )
    val errorCode: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=MESSAGE. Human-readable message name (e.g. \"shipment confirmation\").",
    )
    val messageName: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=SIGNAL. Human-readable broadcast name (e.g. \"settlement complete\").",
    )
    val signalName: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=ESCALATION. Stable business escalation code (e.g. \"APPROVAL_OVERDUE\").",
    )
    val escalationCode: String? = null,
)

@JsonClassDescription(
    "Intermediate throw event emitted in the middle of the process. Set `kind` and populate " +
        "the matching payload field: MESSAGE → messageName, SIGNAL → signalName, " +
        "ESCALATION → escalationCode.",
)
public data class FlatContractIntermediateThrow(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(INTERMEDIATE_THROW_ID_DESCRIPTION)
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(INTERMEDIATE_THROW_NAME_DESCRIPTION)
    val name: String,
    @get:JsonPropertyDescription(
        "Intermediate throw kind. MESSAGE sends a point-to-point message mid-flow, SIGNAL broadcasts " +
            "mid-flow, ESCALATION raises a non-interrupting business escalation mid-flow.",
    )
    val kind: FlatIntermediateThrowKind,
    @field:Size(max = 10)
    @get:JsonPropertyDescription(INTERMEDIATE_THROW_SOURCE_IDS_DESCRIPTION)
    val sourceIds: List<String> = emptyList(),
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Required when kind=MESSAGE. Human-readable message name.")
    val messageName: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Required when kind=SIGNAL. Human-readable broadcast signal name.")
    val signalName: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Required when kind=ESCALATION. Stable business escalation code.")
    val escalationCode: String? = null,
)

@JsonClassDescription(
    "Branch out of a contract decision. Set `kind` to CONDITIONAL (populate condition) for " +
        "branches of EXCLUSIVE decisions, DEFAULT for the catch-all of an EXCLUSIVE decision, " +
        "UNCONDITIONAL for branches of a PARALLEL decision, or EVENT_GATEWAY for branches of an " +
        "EVENT_BASED decision (populate triggerKind + triggerDetail).",
)
public data class FlatContractBranch(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable branch id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Branch label. For DEFAULT branches it still describes the destination (e.g. \"Manual " +
            "review\"); the catch-all intent is expressed by kind=DEFAULT, not by inventing a condition.",
    )
    val label: String,
    @get:JsonPropertyDescription(
        "Branch kind. CONDITIONAL (default on EXCLUSIVE; populate condition), DEFAULT (catch-all " +
            "on EXCLUSIVE; no condition; at most one per decision), UNCONDITIONAL (on PARALLEL; no " +
            "condition; every branch fires concurrently), EVENT_GATEWAY (on EVENT_BASED; populate " +
            "triggerKind + triggerDetail; no condition). Kind/decision matching is strict.",
    )
    val kind: FlatBranchKind,
    @field:Size(max = 500)
    @get:JsonPropertyDescription(
        "Required when kind=CONDITIONAL. Condition expression that selects this branch.",
    )
    val condition: String? = null,
    @get:JsonPropertyDescription(
        "Required when kind=EVENT_GATEWAY. The event that selects this branch: TIMER (deadline), " +
            "MESSAGE (named message arrives), or SIGNAL (broadcast observed).",
    )
    val triggerKind: EventTriggerKind? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Required when kind=EVENT_GATEWAY. Event detail: an ISO-8601 duration for TIMER, or the " +
            "message / signal name for MESSAGE / SIGNAL.",
    )
    val triggerDetail: String? = null,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Optional id of the next activity, decision, or end state this branch leads to. Omit for " +
            "sequential continuation. Use to express loop back-edges and multi-exit topologies.",
    )
    val nextRef: String? = null,
)

@JsonClassDescription(
    "Typed process trigger. Set `type` and populate the matching kind-specific fields: TIMER → " +
        "timerKind + expression, MESSAGE → messageName, SIGNAL → signalName. NONE leaves them null.",
)
public data class FlatContractTrigger(
    @get:JsonPropertyDescription(
        "Trigger type. NONE (ordinary untyped start), TIMER (schedule language; populate timerKind " +
            "+ expression), MESSAGE (webhook/API/event-bus intended for one process; populate " +
            "messageName), SIGNAL (broadcast / multi-listener; populate signalName).",
    )
    val type: FlatTriggerKind,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable description of the trigger from the source prose.")
    val description: String,
    @get:JsonPropertyDescription("Required when type=TIMER. The BpmnTimerKind discriminator (DATE/DURATION/CYCLE).")
    val timerKind: BpmnTimerKind? = null,
    @get:JsonPropertyDescription(
        "Required when type=TIMER. The schedule expression (ISO-8601 date, duration, or cron-like).",
    )
    val expression: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=MESSAGE. Human-readable name of the inbound message (e.g. \"order.submitted\").",
    )
    val messageName: String? = null,
    @get:JsonPropertyDescription(
        "Required when type=SIGNAL. Human-readable name of the broadcast signal.",
    )
    val signalName: String? = null,
)

@JsonClassDescription("Source-grounded process start declaration")
public data class FlatContractStart(
    @field:Valid
    @get:JsonPropertyDescription("Typed start trigger semantics")
    val trigger: FlatContractTrigger,
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Source ids grounding the trigger in source evidence")
    val sourceIds: List<String> = emptyList(),
)

@JsonClassDescription(
    "Source-grounded process contract extracted before BPMN generation. Flat wire shape: each " +
        "sealed hierarchy is collapsed to a single object with a `kind` discriminator and optional " +
        "kind-specific fields. The agent maps this to the internal sealed ProcessContract before " +
        "validation and downstream BPMN generation.",
)
public data class FlatProcessContract(
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
    val start: FlatContractStart,
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Activities required by the process contract")
    val activities: List<FlatContractActivity>,
    @field:Valid
    @field:Size(max = 100)
    @get:JsonPropertyDescription("Decisions and branch points required by the process contract")
    val decisions: List<FlatContractDecision> = emptyList(),
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
    val endStates: List<FlatContractEndState>,
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Intermediate throw events required in the middle of the process")
    val intermediateThrows: List<FlatContractIntermediateThrow> = emptyList(),
    @field:Valid
    @field:Size(max = 50)
    @get:JsonPropertyDescription("Assumptions made while extracting the contract")
    val assumptions: List<ContractAssumption> = emptyList(),
)

@JsonClassDescription("Decision required by the extracted process contract")
public data class FlatContractDecision(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable decision id")
    val id: String,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription(
        "Decision question from the workflow. For PARALLEL decisions this still names the split " +
            "(e.g. 'Run all preparation tracks') even though there is no conditional choice.",
    )
    val question: String,
    @field:NotEmpty
    @field:Valid
    @field:Size(max = 20)
    @get:JsonPropertyDescription("Branches that can be taken from this decision")
    val branches: List<FlatContractBranch>,
    @get:JsonPropertyDescription(
        "How the branches relate. EXCLUSIVE (default) = exactly one branch taken based on its " +
            "condition (a choice, check, or alternative paths where only one option is taken). " +
            "INCLUSIVE = one or more branches whose conditions are true activate concurrently — " +
            "keywords: 'any of the following can fire', 'either, both, or neither', 'each evaluated " +
            "independently'; use for independent optional add-ons that may apply singly, together, " +
            "or not at all. PARALLEL = all branches activate concurrently regardless of conditions " +
            "and reconverge at a join — keywords: 'in parallel', 'simultaneously', 'all of the " +
            "following must complete'. EVENT_BASED = the flow waits for several events and the " +
            "first to fire selects its branch — keywords: 'whichever arrives first', 'if a " +
            "confirmation arrives, or if nothing within N minutes'; its branches are EVENT_GATEWAY " +
            "branches naming the awaited event rather than a condition. " +
            "Differentiator: if every branch fires regardless of " +
            "conditions use PARALLEL; if each fires only when its condition holds use INCLUSIVE. " +
            "Do NOT use PARALLEL for sequential steps that merely share an actor or context — " +
            "parallel means truly concurrent, with no fixed ordering.",
    )
    val kind: ContractGatewayKind = ContractGatewayKind.EXCLUSIVE,
    @field:Size(max = 10)
    @get:JsonPropertyDescription("Source ids grounding this decision in evidence.")
    val sourceIds: List<String> = emptyList(),
)

private const val ACTIVITY_ID_DESCRIPTION: String = "Stable activity id"
private const val ACTIVITY_NAME_DESCRIPTION: String = "Activity name from the workflow"
private const val ACTIVITY_ACTOR_ID_DESCRIPTION: String = "Optional actor id responsible for the activity"
private const val ACTIVITY_SOURCE_IDS_DESCRIPTION: String =
    "Source ids grounding this activity in evidence. Each is an assessment evidence id, " +
        "a clarification questionId, or a literal input-text marker."
private const val END_STATE_ID_DESCRIPTION: String = "Stable end-state id"
private const val END_STATE_NAME_DESCRIPTION: String = "End-state name"
private const val END_STATE_SOURCE_IDS_DESCRIPTION: String =
    "Source ids grounding this end state in evidence."
private const val INTERMEDIATE_THROW_ID_DESCRIPTION: String = "Stable intermediate throw id"
private const val INTERMEDIATE_THROW_NAME_DESCRIPTION: String = "Intermediate throw name"
private const val INTERMEDIATE_THROW_SOURCE_IDS_DESCRIPTION: String =
    "Source ids grounding this intermediate throw in evidence."
