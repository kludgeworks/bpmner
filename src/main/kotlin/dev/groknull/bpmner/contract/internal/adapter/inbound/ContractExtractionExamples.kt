/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractGatewayKind

/**
 * Typed few-shot examples attached to the contract-extraction call via
 * `Creating<FlatProcessContract>.withExample(...)`. They teach the discrimination boundaries
 * and topology idioms that GPT-4.1 does not reliably reproduce from keyword descriptions alone:
 *
 * 1. MESSAGE end state (process ends by sending a message — NOT a NORMAL end)
 * 2. SEND activity (fire-and-forget outbound — NOT a SERVICE task)
 * 3. Intermediate throw (mid-flow send that does NOT end the process)
 * 4. SEND activity + NORMAL end (counter-example: in-flow send followed by ordinary completion)
 * 5. INCLUSIVE gateway (any combination of optional branches may fire)
 * 6. BUSINESS_RULE activity (decision table / rules engine — NOT a SERVICE task)
 * 7. Embedded subprocess, including how its `flows` cross the group boundary
 * 8. PARALLEL fork, including that `start` keeps exactly one outgoing edge
 *
 * Examples 7 and 8 carry a complete `flows` list deliberately: the topology rules they
 * demonstrate (V11 boundary crossing, and start-arity on a concurrent fork) are the ones
 * extraction most often violates, and a flow-less example teaches none of them.
 *
 * As typed values the compiler keeps them structurally valid as the schema evolves.
 * The framework renders them into the prompt in the same JSON shape the LLM must emit.
 * Node ids are named constants so a typo is a compile error.
 *
 * @see dev.groknull.bpmner.authoring.internal.adapter.inbound.GenerationExamples
 */
internal object ContractExtractionExamples {

    const val MESSAGE_END_LABEL: String =
        "MESSAGE throw event with no further steps: declare it in throwEvents, not as a NORMAL end state" +
            " — whether it ends the process is derived from flows, not chosen"

    const val SEND_TASK_LABEL: String =
        "SEND activity: fire-and-forget outbound notification — use kind=SEND, not SERVICE"

    const val INTERMEDIATE_THROW_LABEL: String =
        "MESSAGE throw event with further steps after it: declare it in throwEvents with an outgoing" +
            " flow — not endStates or activities; placement as mid-flow follows automatically"

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

    const val PARALLEL_GATEWAY_LABEL: String =
        "PARALLEL fork: concurrent strands that all run and then reconverge — one PARALLEL decision" +
            " with UNCONDITIONAL branches; `start` still has exactly ONE outgoing edge, into the decision"

    // ──────────────────────────────────────────────────────────────────────────
    // Shared node ids
    // ──────────────────────────────────────────────────────────────────────────

    private const val ACT_VALIDATE = "act-validate"
    private const val ACT_PREPARE_INVOICE = "act-prepare-invoice"
    private const val ACT_SEND_INVOICE = "act-send-invoice"
    private const val ACT_PROCESS = "act-process"
    private const val ACT_SEND_CONFIRM = "act-send-confirmation"
    private const val ACT_ARCHIVE = "act-archive"
    private const val ACT_NOTIFY_MANAGER = "act-notify-manager"
    private const val DEC_NOTIFICATIONS = "dec-notifications"
    private const val END_NORMAL = "end-complete"
    private const val END_MESSAGE = "end-invoice-sent"
    private const val THROW_BILLING = "throw-billing-notification"

    // ──────────────────────────────────────────────────────────────────────────
    // Example 1 — MESSAGE throw event with no further steps (resolves to an end)
    //
    // Prose: "The process begins when started. When everything is done, the process
    //          wraps up by sending a final invoice."
    // The terminal action IS the send → throwEvents entry, kind = MESSAGE. No flow
    // leaves it, so it resolves to an end automatically — nothing here chooses that.
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
                FlatContractActivity(
                    id = ACT_PREPARE_INVOICE,
                    name = "Prepare invoice",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
            ),
            throwEvents = listOf(
                FlatContractThrowEvent(
                    id = END_MESSAGE,
                    name = "Final invoice sent",
                    kind = FlatThrowEventKind.MESSAGE,
                    messageName = "final invoice",
                    sourceIds = listOf("src-1"),
                ),
            ),
            flows = listOf(
                FlatContractFlow(from = "start", to = ACT_VALIDATE),
                FlatContractFlow(from = ACT_VALIDATE, to = ACT_PREPARE_INVOICE),
                FlatContractFlow(from = ACT_PREPARE_INVOICE, to = END_MESSAGE),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 2 — SEND activity (not SERVICE)
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
    // Example 3 — MESSAGE throw event with further steps (resolves to mid-flow)
    //
    // Prose: "The process starts when requested. The system sends a confirmation message
    //          to billing without ending the process. Then the process completes normally."
    // The send is mid-flow → throwEvents entry, kind = MESSAGE. It has an outgoing flow
    // (to the archive step), so it resolves to an intermediate throw automatically.
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
            throwEvents = listOf(
                FlatContractThrowEvent(
                    id = THROW_BILLING,
                    name = "Billing confirmation sent",
                    kind = FlatThrowEventKind.MESSAGE,
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
            flows = listOf(
                FlatContractFlow(from = "start", to = ACT_PROCESS),
                FlatContractFlow(from = ACT_PROCESS, to = THROW_BILLING),
                FlatContractFlow(from = THROW_BILLING, to = ACT_ARCHIVE),
                FlatContractFlow(from = ACT_ARCHIVE, to = END_NORMAL),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 4 — SEND activity + NORMAL end (counter-example)
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
            flows = listOf(
                FlatContractFlow(from = "start", to = ACT_SEND_INVOICE),
                FlatContractFlow(from = ACT_SEND_INVOICE, to = ACT_ARCHIVE),
                FlatContractFlow(from = ACT_ARCHIVE, to = END_NORMAL),
            ),
        )
    // ──────────────────────────────────────────────────────────────────────────
    // Example 5 — INCLUSIVE gateway
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
                    id = DEC_NOTIFICATIONS,
                    question = "Which notifications apply?",
                    kind = ContractGatewayKind.INCLUSIVE,
                    branches = listOf(
                        FlatContractBranch(
                            id = "br-customer",
                            label = "Customer notification",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "customer notification is enabled",
                        ),
                        FlatContractBranch(
                            id = "br-manager",
                            label = "Manager notification",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "manager notification is enabled",
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
            flows = listOf(
                FlatContractFlow(from = "start", to = DEC_NOTIFICATIONS),
                FlatContractFlow(from = DEC_NOTIFICATIONS, to = ACT_SEND_CONFIRM, branchId = "br-customer"),
                FlatContractFlow(from = DEC_NOTIFICATIONS, to = ACT_NOTIFY_MANAGER, branchId = "br-manager"),
                FlatContractFlow(from = ACT_SEND_CONFIRM, to = END_NORMAL),
                FlatContractFlow(from = ACT_NOTIFY_MANAGER, to = END_NORMAL),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 6 — BUSINESS_RULE activity
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
    // Example 7 — embedded subprocess
    //
    // Prose: "To assess a claim, the adjuster validates the documents and then judges whether
    //          the damage is major. Minor damage is settled from a desk estimate; major damage
    //          is sent for a full survey. Once the claim has been assessed, it is paid."
    // The assessment steps are a named composite step → a subProcesses entry grouping them.
    // The members stay in their top-level arrays; the subprocess only names which ids it groups.
    //
    // The interior deliberately BRANCHES and its two branches deliberately END inside the group,
    // because that is the shape the boundary rule is most often got wrong on. A linear interior
    // cannot teach it: when the chain merely stops, there is no way to see whether the exit edge
    // was omitted by rule or by accident, and a model reading such an example generalises that
    // several finishing branches should be joined back to the subprocess's own id to "converge"
    // them. Here the omission is unmistakable — two branches end and NEITHER is joined back.
    // ──────────────────────────────────────────────────────────────────────────

    private const val SUB_ASSESS = "sub-assess-claim"
    private const val ACT_VALIDATE_DOCS = "act-validate-documents"
    private const val DEC_DAMAGE_MAJOR = "dec-damage-major"
    private const val ACT_DESK_ESTIMATE = "act-estimate-from-desk"
    private const val ACT_FULL_SURVEY = "act-commission-full-survey"
    private const val END_DESK_SETTLED = "end-settled-at-desk"
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
                    id = ACT_DESK_ESTIMATE,
                    name = "Estimate from desk",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_FULL_SURVEY,
                    name = "Commission full survey",
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
            // A decision is a member like any other: it stays in `decisions` and the subprocess
            // merely names it. Grouping is by membership, not by nesting the object.
            decisions = listOf(
                FlatContractDecision(
                    id = DEC_DAMAGE_MAJOR,
                    question = "Is the damage major?",
                    kind = ContractGatewayKind.EXCLUSIVE,
                    branches = listOf(
                        FlatContractBranch(
                            id = "br-minor",
                            label = "Minor damage",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "the damage is minor",
                        ),
                        FlatContractBranch(
                            id = "br-major",
                            label = "Major damage",
                            kind = FlatBranchKind.DEFAULT,
                        ),
                    ),
                    sourceIds = listOf("src-1"),
                ),
            ),
            subProcesses = listOf(
                FlatContractSubProcess(
                    id = SUB_ASSESS,
                    name = "Assess claim",
                    memberIds = listOf(
                        ACT_VALIDATE_DOCS,
                        DEC_DAMAGE_MAJOR,
                        ACT_DESK_ESTIMATE,
                        ACT_FULL_SURVEY,
                        END_DESK_SETTLED,
                    ),
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
                // An end state may itself be a member. This is the only landing place available to
                // a branch that finishes the group: a branch cannot be left unrealised, and it
                // cannot point out of the subprocess, so without a nested end state a "nothing
                // further is needed" branch has nowhere legal to go.
                FlatContractEndState(
                    id = END_DESK_SETTLED,
                    name = "Settled at desk",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
            // The boundary rule this example exists to teach, shown rather than stated: the outer
            // flow enters and leaves through the subprocess's OWN id, and the only edges naming a
            // member are member-to-member. An edge with exactly one endpoint inside the group is
            // what V11 (FLOW_CROSSES_SUBPROCESS_BOUNDARY) rejects.
            //
            // Both ways an interior path may finish are shown, because a branch and a plain step
            // finish differently:
            //  - ACT_FULL_SURVEY is a plain step at the end of its path, so it simply has no
            //    outgoing edge. Read the edges and note the absence.
            //  - the minor path reaches END_DESK_SETTLED, an end state that is itself a member.
            //    A branch cannot finish by having no edge — every branch must be realised — so a
            //    nested end state is what a finishing branch points at.
            // Joining either back to SUB_ASSESS would name a member and its own subprocess on one
            // edge, which is the violation, not the fix for it.
            flows = listOf(
                FlatContractFlow(from = "start", to = SUB_ASSESS),
                FlatContractFlow(from = ACT_VALIDATE_DOCS, to = DEC_DAMAGE_MAJOR),
                FlatContractFlow(from = DEC_DAMAGE_MAJOR, to = ACT_DESK_ESTIMATE, branchId = "br-minor"),
                FlatContractFlow(from = DEC_DAMAGE_MAJOR, to = ACT_FULL_SURVEY, branchId = "br-major"),
                FlatContractFlow(from = ACT_DESK_ESTIMATE, to = END_DESK_SETTLED),
                FlatContractFlow(from = SUB_ASSESS, to = ACT_PAY_CLAIM),
                FlatContractFlow(from = ACT_PAY_CLAIM, to = END_NORMAL),
            ),
        )

    // ──────────────────────────────────────────────────────────────────────────
    // Example 8 — PARALLEL fork
    //
    // Prose: "When the order is released, the warehouse packs the goods while the office
    //          books the courier. Once both are done, the shipment is dispatched."
    // Two strands that run at the same time → ONE PARALLEL decision with UNCONDITIONAL
    // branches. Note `start` keeps exactly one outgoing edge (into the decision) — wiring
    // start directly to both strands is the single most common way this is got wrong.
    // The strands reconverge implicitly by flowing into the same downstream activity; the
    // synchronising join gateway is synthesised later, not stated in the contract.
    // ──────────────────────────────────────────────────────────────────────────

    private const val DEC_PREPARE = "dec-prepare-shipment"
    private const val ACT_PACK_GOODS = "act-pack-goods"
    private const val ACT_BOOK_COURIER = "act-book-courier"
    private const val ACT_DISPATCH = "act-dispatch-shipment"

    val parallelGatewayExample: FlatProcessContract =
        FlatProcessContract(
            id = "contract-shipment-preparation",
            processName = "Shipment preparation process",
            summary = "Process that packs goods and books a courier concurrently, then dispatches the shipment.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Order released for shipping",
                ),
                sourceIds = listOf("src-1"),
            ),
            activities = listOf(
                FlatContractActivity(
                    id = ACT_PACK_GOODS,
                    name = "Pack goods",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_BOOK_COURIER,
                    name = "Book courier",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
                FlatContractActivity(
                    id = ACT_DISPATCH,
                    name = "Dispatch shipment",
                    kind = FlatActivityKind.USER,
                    sourceIds = listOf("src-1"),
                ),
            ),
            decisions = listOf(
                FlatContractDecision(
                    id = DEC_PREPARE,
                    question = "Run warehouse and office preparation in parallel",
                    kind = ContractGatewayKind.PARALLEL,
                    branches = listOf(
                        FlatContractBranch(
                            id = "br-warehouse",
                            label = "Warehouse preparation",
                            kind = FlatBranchKind.UNCONDITIONAL,
                        ),
                        FlatContractBranch(
                            id = "br-office",
                            label = "Office preparation",
                            kind = FlatBranchKind.UNCONDITIONAL,
                        ),
                    ),
                    sourceIds = listOf("src-1"),
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = END_NORMAL,
                    name = "Shipment dispatched",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = listOf("src-1"),
                ),
            ),
            flows = listOf(
                FlatContractFlow(from = "start", to = DEC_PREPARE),
                FlatContractFlow(from = DEC_PREPARE, to = ACT_PACK_GOODS, branchId = "br-warehouse"),
                FlatContractFlow(from = DEC_PREPARE, to = ACT_BOOK_COURIER, branchId = "br-office"),
                FlatContractFlow(from = ACT_PACK_GOODS, to = ACT_DISPATCH),
                FlatContractFlow(from = ACT_BOOK_COURIER, to = ACT_DISPATCH),
                FlatContractFlow(from = ACT_DISPATCH, to = END_NORMAL),
            ),
        )
}
