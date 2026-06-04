/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractGatewayKind

/**
 * Typed few-shot examples attached to the contract-extraction call via
 * `Creating<FlatProcessContract>.withExample(...)`. They teach the five discrimination
 * boundaries that GPT-4.1 does not reliably reproduce from keyword descriptions alone:
 *
 * 1. MESSAGE end state (process ends by sending a message — NOT a NORMAL end)
 * 2. ESCALATION end state (process ends by escalating — NOT a NORMAL end)
 * 3. SEND activity (fire-and-forget outbound — NOT a SERVICE task)
 * 4. Intermediate throw (mid-flow send that does NOT end the process)
 * 5. SEND activity + NORMAL end (counter-example: in-flow send followed by ordinary completion)
 *
 * As typed values the compiler keeps them structurally valid as the schema evolves.
 * The framework renders them into the prompt in the same JSON shape the LLM must emit.
 * Node ids are named constants so a typo is a compile error.
 *
 * @see dev.groknull.bpmner.generation.internal.adapter.inbound.GenerationExamples
 */
internal object ContractExtractionExamples {

    const val MESSAGE_END_LABEL: String =
        "MESSAGE end state: process ends by sending a specific message — use kind=MESSAGE, not NORMAL"

    const val ESCALATION_END_LABEL: String =
        "ESCALATION end state: process ends by escalating to a manager — use kind=ESCALATION, not NORMAL"

    const val SEND_TASK_LABEL: String =
        "SEND activity: fire-and-forget outbound notification — use kind=SEND, not SERVICE"

    const val INTERMEDIATE_THROW_LABEL: String =
        "Intermediate throw: mid-flow send that does NOT end the process — use intermediateThrows, not endStates or activities"

    const val SEND_THEN_NORMAL_LABEL: String =
        "SEND activity + NORMAL end: an in-flow send (SEND activity) followed by ordinary process completion (NORMAL end)"

    const val INCLUSIVE_GATEWAY_LABEL: String =
        "INCLUSIVE gateway: independent optional branches where any combination may fire — use kind=INCLUSIVE, not EXCLUSIVE"

    const val BUSINESS_RULE_TASK_LABEL: String =
        "BUSINESS_RULE activity: evaluates a decision table or rules engine —" +
            " use kind=BUSINESS_RULE and populate decisionName, not kind=SERVICE"

    const val SUB_PROCESS_LABEL: String =
        "Embedded subprocess: a named group of activities handled as one composite step —" +
            " list its member ids in a subProcesses entry; members stay in the activities array"

    // ──────────────────────────────────────────────────────────────────────────
    // Shared node ids
    // ──────────────────────────────────────────────────────────────────────────

    private const val ACT_VALIDATE = "act-validate"
    private const val ACT_SEND_INVOICE = "act-send-invoice"
    private const val ACT_REVIEW = "act-review"
    private const val ACT_PROCESS = "act-process"
    private const val ACT_SEND_CONFIRM = "act-send-confirmation"
    private const val ACT_ARCHIVE = "act-archive"
    private const val ACT_NOTIFY_MANAGER = "act-notify-manager"
    private const val END_NORMAL = "end-complete"
    private const val END_MESSAGE = "end-invoice-sent"
    private const val END_ESCALATION = "end-overdue"
    private const val THROW_BILLING = "throw-billing-notification"

    // ──────────────────────────────────────────────────────────────────────────
    // Example 1 — MESSAGE end state
    //
    // Prose: "The process begins when started. When everything is done, the process
    //          wraps up by sending a final invoice."
    // The terminal action IS the send → end state kind = MESSAGE.
    // ──────────────────────────────────────────────────────────────────────────

    val messageEndExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-invoice",
            processName = "Invoice process",
            summary = "Process that concludes by sending a final invoice to the customer.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Process started",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_VALIDATE,
                    name = "Validate order",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_MESSAGE,
                    name = "Final invoice sent",
                    kind = FlatEndStateKind.MESSAGE,
                    messageName = "final invoice",
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 2 — ESCALATION end state
    //
    // Prose: "The process begins when started. If the approval is overdue, we trigger
    //          a manager escalation."
    // The process terminates by escalating → end state kind = ESCALATION.
    // ──────────────────────────────────────────────────────────────────────────

    val escalationEndExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-approval",
            processName = "Approval process",
            summary = "Process that ends with a manager escalation when approval is overdue.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Process started",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_REVIEW,
                    name = "Review approval request",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_ESCALATION,
                    name = "Approval overdue escalation",
                    kind = FlatEndStateKind.ESCALATION,
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 3 — SEND activity (not SERVICE)
    //
    // Prose: "When the registration is complete, the application sends a confirmation
    //          email to the user. Then the process completes."
    // The act of sending is an activity (fire-and-forget) → kind = SEND.
    // The process ends normally after sending → end state kind = NORMAL.
    // ──────────────────────────────────────────────────────────────────────────

    val sendTaskExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-registration",
            processName = "Registration process",
            summary = "Process that sends a confirmation email and then ends normally.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Registration complete",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_SEND_CONFIRM,
                    name = "Send confirmation email",
                    kind = FlatActivityKind.SEND,
                    messageName = "confirmation email",
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Registration complete",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 4 — Intermediate throw (mid-flow; does NOT end the process)
    //
    // Prose: "The process starts when requested. The system sends a confirmation message
    //          to billing without ending the process. Then the process completes normally."
    // The send is mid-flow → intermediateThrows entry; the process ends normally → NORMAL end.
    // ──────────────────────────────────────────────────────────────────────────

    val intermediateThrowExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-billing-notify",
            processName = "Billing notification process",
            summary = "Process that sends a mid-flow message to billing before completing normally.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Process requested",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_PROCESS,
                    name = "Process request",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_ARCHIVE,
                    name = "Archive request",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            intermediateThrows = listOf(
                FlatContractIntermediateThrow(
                    id = THROW_BILLING,
                    name = "Billing confirmation sent",
                    kind = FlatIntermediateThrowKind.MESSAGE,
                    messageName = "billing confirmation",
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Process complete",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 5 — SEND activity + NORMAL end (counter-example)
    //
    // This contrasts with Example 1 (MESSAGE end): here the SEND is an in-flow
    // activity and the process then ends NORMALLY. The difference: if there are
    // further steps after the send, the send is an activity (not an end state).
    // ──────────────────────────────────────────────────────────────────────────

    val sendThenNormalExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-invoice-then-archive",
            processName = "Invoice and archive process",
            summary = "Process that sends an invoice mid-flow, archives the record, then ends normally.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Process started",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_SEND_INVOICE,
                    name = "Send invoice",
                    kind = FlatActivityKind.SEND,
                    messageName = "invoice",
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_ARCHIVE,
                    name = "Archive record",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Process complete",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )
    // ──────────────────────────────────────────────────────────────────────────
    // Example 6 — INCLUSIVE gateway
    //
    // Prose: "Depending on the application, we may send an optional customer
    //          notification AND/OR an optional manager notification."
    // Independent optional branches → INCLUSIVE gateway, not EXCLUSIVE.
    // Keywords that trigger INCLUSIVE: AND/OR, either, both, or neither,
    // each evaluated independently, any combination.
    // ──────────────────────────────────────────────────────────────────────────

    val inclusiveGatewayExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-notifications",
            processName = "Optional notifications process",
            summary = "Process that independently evaluates whether to send a customer and/or manager notification.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Application submitted",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_SEND_CONFIRM,
                    name = "Send customer notification",
                    kind = FlatActivityKind.SEND,
                    messageName = "customer notification",
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_NOTIFY_MANAGER,
                    name = "Send manager notification",
                    kind = FlatActivityKind.SEND,
                    messageName = "manager notification",
                    sourceIds = listOf("src-1"),
                ),
            ),
            decisions = listOf(
                FlatContractDecision(
                    id = "dec-notifications",
                    question = "Which notifications apply?",
                    kind = ContractGatewayKind.INCLUSIVE,
                    branches = listOf(
                        FlatContractBranch(
                            id = "br-customer",
                            label = "Customer notification",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "customer notification is enabled",
                            nextRef = ACT_SEND_CONFIRM,
                        ),
                        FlatContractBranch(
                            id = "br-manager",
                            label = "Manager notification",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "manager notification is enabled",
                            nextRef = ACT_NOTIFY_MANAGER,
                        ),
                    ),
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Notifications complete",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 7 — BUSINESS_RULE activity
    //
    // Prose: "The system evaluates the pricing rules using the order discount
    //          table to determine the final price."
    // Decision table / rules engine → kind=BUSINESS_RULE with decisionName,
    // NOT kind=SERVICE. Keywords: evaluates the rule set, applies the policy,
    // decision table, DMN decision, rules engine.
    // ──────────────────────────────────────────────────────────────────────────

    private const val ACT_EVAL_PRICING = "act-evaluate-pricing"
    private const val ACT_CONFIRM_ORDER = "act-confirm-order"

    val businessRuleTaskExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-pricing",
            processName = "Order pricing process",
            summary = "Process that evaluates pricing rules via decision table and then confirms the order.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Order placed",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_EVAL_PRICING,
                    name = "Evaluate pricing rules",
                    kind = FlatActivityKind.BUSINESS_RULE,
                    decisionName = "order discount table",
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_CONFIRM_ORDER,
                    name = "Confirm order",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Order confirmed",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 8 — embedded subprocess
    //
    // Prose: "To assess a claim, the adjuster validates the documents, estimates the
    //          damage, then decides the payout. Once the claim has been assessed, it is paid."
    // The three assessment steps are a named composite step → a subProcesses entry grouping
    // them. The members stay in `activities`; the subprocess only names which ids it contains.
    // ──────────────────────────────────────────────────────────────────────────

    private const val SUB_ASSESS = "sub-assess-claim"
    private const val ACT_VALIDATE_DOCS = "act-validate-documents"
    private const val ACT_ESTIMATE = "act-estimate-damage"
    private const val ACT_DECIDE_PAYOUT = "act-decide-payout"
    private const val ACT_PAY_CLAIM = "act-pay-claim"

    val subProcessExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-claim-assessment",
            processName = "Claim assessment process",
            summary = "Process that assesses a claim as one composite step, then pays it.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Claim submitted",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_VALIDATE_DOCS,
                    name = "Validate documents",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_ESTIMATE,
                    name = "Estimate damage",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_DECIDE_PAYOUT,
                    name = "Decide payout",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_PAY_CLAIM,
                    name = "Pay claim",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            subProcesses = listOf(
                FlatContractSubProcess(
                    id = SUB_ASSESS,
                    name = "Assess claim",
                    activityIds = listOf(ACT_VALIDATE_DOCS, ACT_ESTIMATE, ACT_DECIDE_PAYOUT),
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Claim paid",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
        )
}
