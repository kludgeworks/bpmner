/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

/**
 * Kind-aware naming-shape advice for the generator and repair prompts. Codifies the
 * preferences encoded by the linter rules (`evt-event-state-name`, `act-verb-object-name`,
 * `gtw-diverging-gateway-question`, …) as plain-text examples the LLM can follow upstream,
 * so it emits compliant names on attempt 1 instead of triggering a repair round.
 *
 * The shape advice is intentionally separate from [BpmnNodeNamingPolicy]: the policy answers
 * "is a name required?" (a hard rule that drives validation), while this advice answers
 * "what shape should the name take?" (a soft recommendation that drives prompts and the
 * downstream repair LLM).
 *
 * Online consensus from BPMN tooling vendors converges on these conventions:
 *   - End events use past-tense state form ("Offer issued"), not action verbs ("Issue offer").
 *     See https://docs.camunda.io/docs/components/best-practices/modeling/naming-bpmn-elements/
 *     and https://www.trisotech.com/naming-conventions-for-bpmn-diagrams/
 *   - Start events name the event that triggered the process ("Order received").
 *   - Tasks are imperative verb-object ("Validate request").
 *   - Diverging gateways ask a yes/no or which-of question ending in `?`.
 *   - Converging gateways are unnamed (handled by [BpmnNodeNamingPolicy]).
 *
 * The `when (node)` is exhaustive over [BpmnNode], so any new sealed subtype added by the
 * vocabulary-extension epic (#196) — typed start/end events, intermediate events, boundary
 * events, subprocesses, etc. — forces a compile-time decision about its naming shape.
 */
internal object BpmnNamingShapeAdvice {
    data class Advice(
        val kind: String,
        val shape: String,
        val examples: List<String>,
        val antiExamples: List<String>,
    )

    /**
     * Returns the [Advice] for this [node]'s kind, or null when the kind has no naming-shape
     * opinion today (e.g. converging gateways, which are unnamed by policy).
     */
    @Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
    fun adviceFor(node: BpmnNode): Advice? = when (node) {
        is BpmnStartEvent -> START_EVENT_ADVICE
        is BpmnEndEvent -> END_EVENT_ADVICE
        is BpmnUserTask -> USER_TASK_ADVICE
        is BpmnServiceTask -> SERVICE_TASK_ADVICE
        is BpmnScriptTask -> SCRIPT_TASK_ADVICE
        is BpmnBusinessRuleTask -> BUSINESS_RULE_TASK_ADVICE
        is BpmnSendTask -> SEND_TASK_ADVICE
        is BpmnReceiveTask -> RECEIVE_TASK_ADVICE
        is BpmnManualTask -> MANUAL_TASK_ADVICE
        is BpmnExclusiveGateway -> EXCLUSIVE_GATEWAY_ADVICE
        is BpmnParallelGateway -> PARALLEL_GATEWAY_ADVICE
        is BpmnIntermediateCatchEvent -> INTERMEDIATE_CATCH_EVENT_ADVICE
        is BpmnIntermediateThrowEvent -> INTERMEDIATE_THROW_EVENT_ADVICE
        is BpmnBoundaryEvent -> BOUNDARY_EVENT_ADVICE
    }

    /**
     * All advice entries by kind discriminator name, in a stable display order suitable for
     * appending to a generator prompt.
     */
    fun allAdvice(): List<Advice> = listOf(
        START_EVENT_ADVICE,
        INTERMEDIATE_CATCH_EVENT_ADVICE,
        INTERMEDIATE_THROW_EVENT_ADVICE,
        BOUNDARY_EVENT_ADVICE,
        END_EVENT_ADVICE,
        USER_TASK_ADVICE,
        SERVICE_TASK_ADVICE,
        SCRIPT_TASK_ADVICE,
        BUSINESS_RULE_TASK_ADVICE,
        SEND_TASK_ADVICE,
        RECEIVE_TASK_ADVICE,
        MANUAL_TASK_ADVICE,
        EXCLUSIVE_GATEWAY_ADVICE,
        PARALLEL_GATEWAY_ADVICE,
    )

    /**
     * Returns the [Advice] keyed by lint-rule id. Used by the repair prompt to surface a
     * specific shape recommendation when a known naming rule fires (e.g. `evt-event-state-name`).
     *
     * Several rules apply to multiple [BpmnNode] subtypes — e.g. `evt-event-state-name`
     * targets start, intermediate-catch, intermediate-throw, and end events. Returning the
     * single-kind advice ([END_EVENT_ADVICE]) for these would put a misleading `kind` label
     * in the repair-prompt hint when the rule fires on a start or intermediate event. We
     * return a synthetic [Advice] whose `kind` field reads inclusively (e.g.
     * `"EVENT (start / intermediate / end)"`) and whose `shape`/examples are still accurate
     * because the rule has uniform semantics across the kinds it targets.
     */
    fun adviceForRule(ruleId: String): Advice? = when (ruleId) {
        "bpmner/evt-event-state-name", "bpmner/evt-event-state-pattern" -> EVENT_STATE_ADVICE
        "bpmner/act-verb-object-name" -> TASK_VERB_OBJECT_ADVICE
        "bpmner/gtw-diverging-gateway-question" -> EXCLUSIVE_GATEWAY_ADVICE
        else -> null
    }

    private val START_EVENT_ADVICE =
        Advice(
            kind = "START_EVENT",
            shape = "Trigger event in past-participle / state form (subject + received/fired/arrived).",
            examples = listOf("Order received", TIMER_FIRED, "Application submitted"),
            antiExamples = listOf("Receive order", TRIGGER_TIMER, "Submit application"),
        )

    private val END_EVENT_ADVICE =
        Advice(
            kind = "END_EVENT",
            shape =
            "Past-tense state describing the terminal state the process reached." +
                " Object + past-participle verb. Never start with an imperative verb.",
            examples = listOf("Offer issued", "Application declined", "Decline letter written"),
            antiExamples = listOf("Issue offer", "Decline application", "Write decline letter"),
        )

    private val USER_TASK_ADVICE =
        Advice(
            kind = "USER_TASK",
            shape = "Imperative verb-object naming the work the human performs.",
            examples = listOf("Validate request", "Approve loan", "Collect documentation"),
            antiExamples = listOf("Validation", "Loan approval", "Documents"),
        )

    private val SERVICE_TASK_ADVICE =
        Advice(
            kind = "SERVICE_TASK",
            shape = "Imperative verb-object naming the work the system performs.",
            examples = listOf(SEND_NOTIFICATION, "Charge card", "Generate offer letter"),
            antiExamples = listOf("Notification", "Card charge", "Letter generation"),
        )

    private val EXCLUSIVE_GATEWAY_ADVICE =
        Advice(
            kind = "EXCLUSIVE_GATEWAY (diverging)",
            shape =
            "Question form ending in '?'. Names the decision being made," +
                " not the routing mechanism. Converging joins are unnamed (policy).",
            examples = listOf("Is the score above 750?", "Which credit tier?", "Did validation pass?"),
            antiExamples = listOf("Routing", "Decision", "Branch on score"),
        )

    private val PARALLEL_GATEWAY_ADVICE =
        Advice(
            kind = "PARALLEL_GATEWAY (diverging)",
            shape =
            "Imperative describing the concurrent action the fork triggers." +
                " Converging joins are unnamed (policy).",
            examples = listOf("Run preparation tracks", "Send notifications in parallel"),
            antiExamples = listOf("Parallel split", "Fork", "AND-split"),
        )

    private val INTERMEDIATE_CATCH_EVENT_ADVICE =
        Advice(
            kind = "INTERMEDIATE_CATCH_EVENT",
            shape =
            "Past-tense state form describing what was caught (same shape as start/end" +
                " events). The event-definition kind (timer / message / signal / error /" +
                " escalation) determines what's caught; the name describes the observed state.",
            examples = listOf("Approval received", TIMER_FIRED, "Confirmation arrived"),
            antiExamples = listOf("Receive approval", TRIGGER_TIMER, "Wait for confirmation"),
        )

    private val INTERMEDIATE_THROW_EVENT_ADVICE =
        Advice(
            kind = "INTERMEDIATE_THROW_EVENT",
            shape =
            "Past-tense form describing the event the process emits (state of having" +
                " thrown). The event-definition kind (message / signal / escalation) determines" +
                " what's thrown; the name describes the emission's effect.",
            examples = listOf("Notification sent", "Escalation raised", "Signal broadcast"),
            antiExamples = listOf(SEND_NOTIFICATION, "Raise escalation", "Broadcast signal"),
        )

    private val BOUNDARY_EVENT_ADVICE =
        Advice(
            kind = "BOUNDARY_EVENT",
            shape =
            "Short event-shaped name describing what the boundary catches on the host" +
                " activity. Past-tense state or noun phrase. The host activity is named" +
                " separately and is not duplicated here.",
            examples = listOf(TIMER_FIRED, "Error caught", "Escalation received"),
            antiExamples = listOf("Cancel approval", "Handle error", "Catch escalation"),
        )

    private val SCRIPT_TASK_ADVICE =
        Advice(
            kind = "SCRIPT_TASK",
            shape =
            "Imperative verb-object naming the computation or transformation the engine" +
                " evaluates inline. Reads like a service task but with no external service.",
            examples = listOf("Normalise address", "Compute loan-to-value ratio", "Convert currencies"),
            antiExamples = listOf("Script", "Run script", "Data normalisation"),
        )

    private val BUSINESS_RULE_TASK_ADVICE =
        Advice(
            kind = "BUSINESS_RULE_TASK",
            shape =
            "Imperative verb-object describing the decision being evaluated, typically" +
                " starting with `Evaluate`, `Determine`, or `Apply` to signal the rule-set" +
                " call. The decision id lives on `decisionRef`.",
            examples = listOf("Evaluate credit policy", "Determine premium tier", "Apply pricing rules"),
            antiExamples = listOf("Decision", "Credit policy", "Rules engine"),
        )

    private val SEND_TASK_ADVICE =
        Advice(
            kind = "SEND_TASK",
            shape =
            "Imperative messaging verb-object describing the outbound message. Reads as" +
                " a fire-and-forget send: no acknowledgement is awaited.",
            examples = listOf("Send decline notification", "Notify customer of approval", "Publish settlement"),
            antiExamples = listOf("Notification", "Send", "Message customer"),
        )

    private val RECEIVE_TASK_ADVICE =
        Advice(
            kind = "RECEIVE_TASK",
            shape =
            "Past-participle wait shape — names what arrived, not the act of waiting." +
                " Shares the past-tense event convention because the task IS a wait-point.",
            examples =
            listOf(
                "Customer acknowledgement received",
                "Payment confirmation received",
                "Counter-signature received",
            ),
            antiExamples =
            listOf(
                "Wait for acknowledgement",
                "Receive payment confirmation",
                "Await counter-signature",
            ),
        )

    private val MANUAL_TASK_ADVICE =
        Advice(
            kind = "MANUAL_TASK",
            shape =
            "Imperative verb-object naming the off-system work the human performs. Same" +
                " shape as a user task; the distinction is that no system UI mediates the work.",
            examples = listOf("Inspect property condition", "File paper documents", "Walk the trading floor"),
            antiExamples = listOf("Inspection", "Manual review", "Off-system work"),
        )

    // Synthetic advice values for rules whose semantics span multiple node kinds. The shape
    // text and examples are uniform across the targets (the rule wouldn't apply otherwise)
    // — only the `kind` label changes to read inclusively.

    private val EVENT_STATE_ADVICE =
        Advice(
            kind = "EVENT (start / intermediate / end)",
            shape =
            "Object + past-participle verb (state form). Never start with an imperative" +
                " verb. Applies to start, intermediate-catch, intermediate-throw, and end events.",
            examples =
            listOf(
                "Order received",
                TIMER_FIRED,
                "Offer issued",
                "Application declined",
                "Decline letter written",
            ),
            antiExamples =
            listOf(
                "Receive order",
                TRIGGER_TIMER,
                "Issue offer",
                "Decline application",
                "Write decline letter",
            ),
        )

    private const val TIMER_FIRED = "Timer fired"
    private const val TRIGGER_TIMER = "Trigger timer"
    private const val SEND_NOTIFICATION = "Send notification"

    private val TASK_VERB_OBJECT_ADVICE =
        Advice(
            kind = "TASK (user / service)",
            shape =
            "Imperative verb-object naming the work performed. Applies equally to user" +
                " tasks (human work) and service tasks (system work).",
            examples =
            listOf(
                "Validate request",
                "Approve loan",
                SEND_NOTIFICATION,
                "Charge card",
                "Generate offer letter",
            ),
            antiExamples =
            listOf(
                "Validation",
                "Loan approval",
                "Notification",
                "Card charge",
                "Letter generation",
            ),
        )
}
