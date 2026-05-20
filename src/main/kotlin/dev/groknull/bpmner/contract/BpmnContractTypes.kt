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
import dev.groknull.bpmner.core.BpmnTimerKind
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
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
        @field:NotBlank
        override val description: String,
    ) : ContractTrigger

    data class Timer(
        val timerKind: BpmnTimerKind,
        @field:NotBlank
        val expression: String,
        @field:NotBlank
        override val description: String,
    ) : ContractTrigger

    data class Message(
        @field:NotBlank
        val messageName: String,
        @field:NotBlank
        override val description: String,
    ) : ContractTrigger

    data class Signal(
        @field:NotBlank
        val signalName: String,
        @field:NotBlank
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
        assumptions = assumptions,
    )
}

/**
 * Activity required by the extracted process contract.
 *
 * Mirrors the sealed-subtype pattern used by [ContractTrigger] and [ContractBranch]:
 * the `kind` discriminator in the LLM-facing JSON dispatches to one of seven subtypes,
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

    @JsonClassDescription("Service activity — external/system automation. Maps to BpmnServiceTask.")
    data class Service(
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Stable activity id")
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Activity name from the workflow")
        override val name: String,
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Optional actor id responsible for the activity")
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription("User activity — human work through a system UI. Maps to BpmnUserTask.")
    data class User(
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Stable activity id")
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Activity name from the workflow")
        override val name: String,
        @field:Size(max = 200)
        @get:JsonPropertyDescription("Optional actor id responsible for the activity")
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription("Script activity — engine-evaluated computation, no external service. Maps to BpmnScriptTask.")
    data class Script(
        @field:NotBlank
        @field:Size(max = 200)
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        override val name: String,
        @field:Size(max = 200)
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription(
        "Business-rule activity — rule-set or decision-table evaluation. Maps to BpmnBusinessRuleTask.",
    )
    data class BusinessRule(
        @field:NotBlank
        @field:Size(max = 200)
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        override val name: String,
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription(
            "Human-readable name of the decision / rule set the LLM identified in the prose " +
                "(e.g. \"credit policy\", \"premium tier\"). The downstream BPMN generator maps " +
                "this to a stable BpmnBusinessRuleTask.decisionRef id.",
        )
        val decisionName: String,
        @field:Size(max = 200)
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription("Send activity — fire-and-forget outbound message. Maps to BpmnSendTask.")
    data class Send(
        @field:NotBlank
        @field:Size(max = 200)
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        override val name: String,
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription(
            "Human-readable name of the message being sent (e.g. \"decline notification\"). " +
                "The downstream BPMN generator maps this to a stable BpmnMessageRef catalogue id.",
        )
        val messageName: String,
        @field:Size(max = 200)
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription(
        "Receive activity — blocks the flow until an inbound message arrives. Maps to BpmnReceiveTask.",
    )
    data class Receive(
        @field:NotBlank
        @field:Size(max = 200)
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        override val name: String,
        @field:NotBlank
        @field:Size(max = 200)
        @get:JsonPropertyDescription(
            "Human-readable name of the awaited message (e.g. \"customer acknowledgement\"). " +
                "The downstream BPMN generator maps this to a stable BpmnMessageRef catalogue id.",
        )
        val messageName: String,
        @field:Size(max = 200)
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
    ) : ContractActivity

    @JsonClassDescription("Manual activity — human work without system support. Maps to BpmnManualTask.")
    data class Manual(
        @field:NotBlank
        @field:Size(max = 200)
        override val id: String,
        @field:NotBlank
        @field:Size(max = 200)
        override val name: String,
        @field:Size(max = 200)
        override val actorId: String? = null,
        @field:Size(max = 10)
        @get:JsonPropertyDescription(ACTIVITY_SOURCE_IDS_DESCRIPTION)
        override val sourceIds: List<String> = emptyList(),
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

private const val ACTIVITY_SOURCE_IDS_DESCRIPTION: String =
    "Source ids grounding this activity in evidence. Each is an assessment evidence id, " +
        "a clarification questionId, or a literal input-text marker."

/**
 * The discriminator string for [activity], matching the `kind` field in the LLM JSON output
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
fun ContractActivity.withSourceIds(sourceIds: List<String>): ContractActivity =
    when (this) {
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
     * All branches activate concurrently — a fork. Branches under PARALLEL decisions have
     * no `condition`; the gateway emits one outbound flow per branch unconditionally, and
     * a matching parallel join gateway synchronises the branches before downstream work
     * proceeds. Maps to `<bpmn:parallelGateway>`.
     */
    PARALLEL,
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
            "condition. PARALLEL = all branches activate concurrently and reconverge at a join. " +
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
 * Mirrors the sealed-subtype pattern established by PR #184 for [dev.groknull.bpmner.core.BpmnNode]:
 * the `kind` discriminator in the LLM-facing JSON dispatches to one of three subtypes, each
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
)
sealed interface ContractBranch {
    val id: String
    val label: String
    val nextRef: String?
}

/**
 * The discriminator string for [branch], matching the `kind` field in the LLM JSON output
 * and the names declared in `@JsonSubTypes` on [ContractBranch].
 */
val ContractBranch.kindName: String
    get() =
        when (this) {
            is ConditionalBranch -> "CONDITIONAL"
            is DefaultBranch -> "DEFAULT"
            is UnconditionalBranch -> "UNCONDITIONAL"
        }

@JsonClassDescription("Conditional branch — taken when `condition` evaluates true")
data class ConditionalBranch(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable branch id")
    override val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Branch label")
    override val label: String,
    @field:NotBlank
    @field:Size(max = 500)
    @get:JsonPropertyDescription("Condition expression that selects this branch. Required.")
    val condition: String,
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Optional id of the next activity, decision, or end state this branch leads to. " +
            "Omit for sequential continuation. Use to express loop back-edges and multi-exit topologies.",
    )
    override val nextRef: String? = null,
) : ContractBranch

@JsonClassDescription(
    "Default (catch-all) branch — taken when no other branch's condition matched. " +
        "Valid on EXCLUSIVE decisions only. At most one per decision.",
)
data class DefaultBranch(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable branch id")
    override val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription(
        "Branch label — describes the destination, e.g. \"Manual review\". The catch-all " +
            "intent is expressed by the DEFAULT kind, not by inventing a placeholder condition.",
    )
    override val label: String,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Optional next-element id; same semantics as on ConditionalBranch.")
    override val nextRef: String? = null,
) : ContractBranch

@JsonClassDescription(
    "Unconditional branch of a PARALLEL decision — fires concurrently with its siblings; " +
        "the matching parallel join synchronises the tracks downstream.",
)
data class UnconditionalBranch(
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Stable branch id")
    override val id: String,
    @field:NotBlank
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Branch label naming this parallel track, e.g. \"IT prep\".")
    override val label: String,
    @field:Size(max = 200)
    @get:JsonPropertyDescription("Optional next-element id; same semantics as on ConditionalBranch.")
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
