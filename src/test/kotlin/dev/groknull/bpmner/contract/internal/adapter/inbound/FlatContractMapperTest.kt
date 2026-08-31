/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions", "LargeClass")

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.bpmn.BoundaryEventKind
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBoundaryEvent
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractFlow
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.EventGatewayBranch
import dev.groknull.bpmner.contract.EventTriggerKind
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import dev.groknull.bpmner.contract.boundaryEvents
import dev.groknull.bpmner.contract.iteration
import dev.groknull.bpmner.contract.loop
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlatContractMapperTest {
    @Test
    fun `every FlatContractActivity kind round-trips to the matching sealed subtype`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractActivity, ContractActivity>> = listOf(
            flatActivity(FlatActivityKind.SERVICE, id = "a-svc") to
                ContractActivity.Service("a-svc", "Activity", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.USER, id = "a-usr") to
                ContractActivity.User("a-usr", "Activity", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.SCRIPT, id = "a-scr") to
                ContractActivity.Script("a-scr", "Activity", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.BUSINESS_RULE, id = "a-br", decisionName = "credit policy") to
                ContractActivity.BusinessRule(
                    "a-br",
                    "Activity",
                    decisionName = "credit policy",
                    sourceIds = sourceIds,
                ),
            flatActivity(FlatActivityKind.SEND, id = "a-snd", messageName = "decline") to
                ContractActivity.Send("a-snd", "Activity", messageName = "decline", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.RECEIVE, id = "a-rcv", messageName = "ack") to
                ContractActivity.Receive("a-rcv", "Activity", messageName = "ack", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.MANUAL, id = "a-man") to
                ContractActivity.Manual("a-man", "Activity", sourceIds = sourceIds),
            flatActivity(FlatActivityKind.CALL_ACTIVITY, id = "a-call", calledElement = "fulfil-order") to
                ContractActivity.CallActivity("a-call", "Activity", calledElement = "fulfil-order", sourceIds = sourceIds),
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
    }

    @Test
    fun `FlatContractSubProcess maps to a ContractActivity SubProcess preserving members`() {
        val flat = FlatContractSubProcess(
            id = "sub-assess",
            name = "Assess claim",
            memberIds = listOf("act-validate", "act-estimate"),
            sourceIds = listOf("ev1"),
        )

        assertEquals(
            ContractActivity.SubProcess(
                id = "sub-assess",
                name = "Assess claim",
                memberIds = listOf("act-validate", "act-estimate"),
                sourceIds = listOf("ev1"),
            ),
            flat.toSealed(),
        )
    }

    @Test
    fun `toSealed appends subProcesses to activities as SubProcess entries`() {
        val flat = FlatProcessContract(
            id = "c-sub",
            processName = "Assess and pay",
            summary = "Assess a claim as one composite step, then pay it.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Claim submitted"),
                sourceIds = listOf("ev1"),
            ),
            activities = listOf(
                flatActivity(FlatActivityKind.USER, id = "act-validate"),
                flatActivity(FlatActivityKind.SERVICE, id = "act-estimate"),
                flatActivity(FlatActivityKind.SERVICE, id = "act-pay"),
            ),
            subProcesses = listOf(
                FlatContractSubProcess(
                    id = "sub-assess",
                    name = "Assess claim",
                    memberIds = listOf("act-validate", "act-estimate"),
                    sourceIds = listOf("ev1"),
                ),
            ),
            endStates = listOf(flatEnd(FlatEndStateKind.NORMAL, "e-ok")),
        )

        val sealed = flat.toSealed()

        // Three leaf activities followed by the appended subprocess.
        assertEquals(4, sealed.activities.size)
        val subProcess = sealed.activities.filterIsInstance<ContractActivity.SubProcess>().single()
        assertEquals("sub-assess", subProcess.id)
        assertEquals(listOf("act-validate", "act-estimate"), subProcess.memberIds)
    }

    @Test
    fun `BUSINESS_RULE without decisionName fails with the offending id in the message`() {
        val flat = flatActivity(FlatActivityKind.BUSINESS_RULE, id = "a-br", decisionName = null)

        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("a-br" in ex.message.orEmpty(), "expected id in message, got: ${ex.message}")
        assertTrue("decisionName" in ex.message.orEmpty())
    }

    @Test
    fun `SEND without messageName fails with the offending id`() {
        val flat = flatActivity(FlatActivityKind.SEND, id = "a-snd", messageName = null)

        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("a-snd" in ex.message.orEmpty())
        assertTrue("messageName" in ex.message.orEmpty())
    }

    @Test
    fun `extra null-or-set fields on the wrong kind are tolerated`() {
        // LLM may emit messageName on a SERVICE activity; the mapper should ignore it, not reject.
        val flat = flatActivity(
            FlatActivityKind.SERVICE,
            id = "a-svc",
            decisionName = "ignored",
            messageName = "ignored",
        )

        val sealed = flat.toSealed() as ContractActivity.Service
        assertEquals("a-svc", sealed.id)
    }

    @Test
    fun `activity iteration round-trips to ContractIteration`() {
        val flat = FlatContractActivity(
            id = "act-mi",
            name = "Pick line item",
            kind = FlatActivityKind.USER,
            iteration = FlatContractIteration(
                mode = MultiInstanceMode.SEQUENTIAL,
                collectionDescription = "each line item on the slip",
            ),
        )

        assertEquals(
            ContractIteration(MultiInstanceMode.SEQUENTIAL, "each line item on the slip"),
            flat.toSealed().iteration,
        )
    }

    @Test
    fun `activity boundary events round-trip to ContractBoundaryEvent`() {
        val flat = FlatContractActivity(
            id = "act-pay",
            name = "Run payment",
            kind = FlatActivityKind.SERVICE,
            boundaryEvents = listOf(
                FlatContractBoundaryEvent(
                    kind = BoundaryEventKind.ERROR,
                    label = "Chargeback raised",
                    detail = "CHARGEBACK",
                ),
            ),
        )

        assertEquals(
            listOf(
                ContractBoundaryEvent(
                    kind = BoundaryEventKind.ERROR,
                    label = "Chargeback raised",
                    detail = "CHARGEBACK",
                    id = "boundary-event",
                ),
            ),
            flat.toSealed().boundaryEvents,
        )
    }

    // Exhaustiveness guard: a kind with no case here is untested by construction. Same pattern
    // as the end-state and intermediate-throw round-trips below.
    @Test
    fun `every BoundaryEventKind round-trips with its kind-specific detail`() {
        val cases: List<Pair<FlatContractBoundaryEvent, ContractBoundaryEvent>> = listOf(
            flatBoundaryEvent(BoundaryEventKind.TIMER, "be-timer", detail = "PT24H") to
                ContractBoundaryEvent(BoundaryEventKind.TIMER, "Label", "PT24H", id = "be-timer"),
            flatBoundaryEvent(BoundaryEventKind.ERROR, "be-error", detail = "CHARGEBACK") to
                ContractBoundaryEvent(BoundaryEventKind.ERROR, "Label", "CHARGEBACK", id = "be-error"),
            flatBoundaryEvent(BoundaryEventKind.ESCALATION, "be-escalation", detail = "APPROVAL_OVERDUE") to
                ContractBoundaryEvent(BoundaryEventKind.ESCALATION, "Label", "APPROVAL_OVERDUE", id = "be-escalation"),
            flatBoundaryEvent(BoundaryEventKind.MESSAGE, "be-message", detail = "order cancellation") to
                ContractBoundaryEvent(BoundaryEventKind.MESSAGE, "Label", "order cancellation", id = "be-message"),
        )
        assertEquals(
            BoundaryEventKind.entries.size,
            cases.size,
            "every BoundaryEventKind needs a case here — a new kind must not slip through untested",
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
    }

    @Test
    fun `activity loop round-trips to ContractLoop`() {
        val flat = FlatContractActivity(
            id = "act-charge",
            name = "Charge card",
            kind = FlatActivityKind.SERVICE,
            loop = FlatContractLoop(testBefore = false, loopCondition = "payment not yet successful", loopMaximum = 3),
        )

        assertEquals(
            ContractLoop(testBefore = false, loopCondition = "payment not yet successful", loopMaximum = 3),
            flat.toSealed().loop,
        )
    }

    @Test
    fun `every FlatContractEndState kind round-trips to the matching sealed subtype`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractEndState, ContractEndState>> = listOf(
            flatEnd(FlatEndStateKind.NORMAL, "e-norm") to
                ContractEndState.Normal("e-norm", "End", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.TERMINATE, "e-term") to
                ContractEndState.Terminate("e-term", "End", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.ERROR, "e-err", payload = "CREDIT_REJECTED") to
                ContractEndState.Error("e-err", "End", errorCode = "CREDIT_REJECTED", sourceIds = sourceIds),
        )
        assertEquals(
            FlatEndStateKind.entries.size,
            cases.size,
            "every FlatEndStateKind needs a case here — a new kind must not slip through untested",
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
    }

    @Test
    fun `every FlatContractThrowEvent kind round-trips via toEndState`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractThrowEvent, ContractEndState>> = listOf(
            flatThrow(FlatThrowEventKind.MESSAGE, "t-msg", "shipped") to
                ContractEndState.Message("t-msg", "Throw", messageName = "shipped", sourceIds = sourceIds),
            flatThrow(FlatThrowEventKind.SIGNAL, "t-sig", "settlement complete") to
                ContractEndState.Signal("t-sig", "Throw", signalName = "settlement complete", sourceIds = sourceIds),
            flatThrow(FlatThrowEventKind.ESCALATION, "t-esc", "APPROVAL_OVERDUE") to
                ContractEndState.Escalation(
                    "t-esc",
                    "Throw",
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = sourceIds,
                ),
        )
        assertEquals(
            FlatThrowEventKind.entries.size,
            cases.size,
            "every FlatThrowEventKind needs a case here — a new kind must not slip through untested",
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toEndState()) }
    }

    @Test
    fun `every FlatContractThrowEvent kind round-trips via toIntermediateThrow`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractThrowEvent, ContractIntermediateThrow>> = listOf(
            flatThrow(FlatThrowEventKind.MESSAGE, "t-msg", "shipped") to
                ContractIntermediateThrow.Message("t-msg", "Throw", messageName = "shipped", sourceIds = sourceIds),
            flatThrow(FlatThrowEventKind.SIGNAL, "t-sig", "settlement complete") to
                ContractIntermediateThrow.Signal(
                    "t-sig",
                    "Throw",
                    signalName = "settlement complete",
                    sourceIds = sourceIds,
                ),
            flatThrow(FlatThrowEventKind.ESCALATION, "t-esc", "APPROVAL_OVERDUE") to
                ContractIntermediateThrow.Escalation(
                    "t-esc",
                    "Throw",
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = sourceIds,
                ),
        )
        assertEquals(
            FlatThrowEventKind.entries.size,
            cases.size,
            "every FlatThrowEventKind needs a case here — a new kind must not slip through untested",
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toIntermediateThrow()) }
    }

    // The actual fix for issue #749: placement is derived from `flows`, not from which array the
    // model chose. Two throw events, otherwise identical, differing only in outgoing-flow count.
    @Test
    fun `toSealed places a throw event with no outgoing flow as an end state`() {
        val flat = FlatProcessContract(
            id = "c-1",
            processName = "Report process",
            summary = "Sends a report and stops.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Requested"),
            ),
            activities = listOf(flatActivity(FlatActivityKind.SERVICE, id = "a-prepare")),
            throwEvents = listOf(flatThrow(FlatThrowEventKind.MESSAGE, "t-report-sent", "final report")),
            flows = listOf(FlatContractFlow(from = "a-prepare", to = "t-report-sent")),
        )

        val sealed = flat.toSealed()

        assertTrue(sealed.endStates.any { it is ContractEndState.Message && it.id == "t-report-sent" })
        assertTrue(sealed.intermediateThrows.none { it.id == "t-report-sent" })
    }

    @Test
    fun `toSealed places a throw event with an outgoing flow as an intermediate throw`() {
        val flat = FlatProcessContract(
            id = "c-1",
            processName = "Report process",
            summary = "Sends a report mid-flow, then continues.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Requested"),
            ),
            activities = listOf(
                flatActivity(FlatActivityKind.SERVICE, id = "a-prepare"),
                flatActivity(FlatActivityKind.SERVICE, id = "a-archive"),
            ),
            throwEvents = listOf(flatThrow(FlatThrowEventKind.MESSAGE, "t-report-sent", "final report")),
            endStates = listOf(flatEnd(FlatEndStateKind.NORMAL, "e-done")),
            flows = listOf(
                FlatContractFlow(from = "a-prepare", to = "t-report-sent"),
                FlatContractFlow(from = "t-report-sent", to = "a-archive"),
                FlatContractFlow(from = "a-archive", to = "e-done"),
            ),
        )

        val sealed = flat.toSealed()

        assertTrue(sealed.intermediateThrows.any { it is ContractIntermediateThrow.Message && it.id == "t-report-sent" })
        assertTrue(sealed.endStates.none { it.id == "t-report-sent" })
    }

    @Test
    fun `ERROR end-state without errorCode fails with the offending id`() {
        val flat = FlatContractEndState(
            id = "e-err",
            name = "End",
            kind = FlatEndStateKind.ERROR,
            sourceIds = listOf("ev1"),
            errorCode = null,
        )
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("e-err" in ex.message.orEmpty())
        assertTrue("errorCode" in ex.message.orEmpty())
    }

    @Test
    fun `kind-required CharSequence fields reject blank values, not just null`() {
        // Blank decisionName on a BUSINESS_RULE activity must be rejected — Jakarta @NotBlank
        // on the sealed constructor is schema-only and would let a blank "" through.
        val flatActivity = flatActivity(
            FlatActivityKind.BUSINESS_RULE,
            id = "a-br",
            decisionName = "   ",
        )
        val activityEx = assertFailsWith<IllegalArgumentException> { flatActivity.toSealed() }
        assertTrue("a-br" in activityEx.message.orEmpty())
        assertTrue("decisionName" in activityEx.message.orEmpty())

        // Same guarantee for trigger fields, which use description (not id) as the error context.
        val flatTrigger = FlatContractTrigger(
            type = FlatTriggerKind.MESSAGE,
            description = "order webhook",
            messageName = "",
        )
        val triggerEx = assertFailsWith<IllegalArgumentException> { flatTrigger.toSealed() }
        assertTrue("order webhook" in triggerEx.message.orEmpty())
        assertTrue("messageName" in triggerEx.message.orEmpty())
    }

    // Round-trip coverage for both FlatContractThrowEvent dispatchers lives with the other
    // exhaustiveness tests above (`every FlatContractThrowEvent kind round-trips via toEndState` /
    // `...via toIntermediateThrow`).
    @Test
    fun `FlatContractThrowEvent required payload fields fail with offending id`() {
        val message = flatThrow(FlatThrowEventKind.MESSAGE, "throw-msg", payload = null)
        val messageEx = assertFailsWith<IllegalArgumentException> { message.toIntermediateThrow() }
        assertTrue("throw-msg" in messageEx.message.orEmpty())
        assertTrue("messageName" in messageEx.message.orEmpty())
    }

    // EventTriggerKind is not mapped to a BpmnEventDefinition anywhere, so no exhaustive `when`
    // guards this enum. Cover every value here instead, or a new kind lands unexercised.
    @Test
    fun `every EventTriggerKind round-trips on an event-gateway branch`() {
        EventTriggerKind.entries.forEach { trigger ->
            val sealed = FlatContractBranch(
                id = "b-evt",
                label = "Event fires",
                kind = FlatBranchKind.EVENT_GATEWAY,
                triggerKind = trigger,
                triggerDetail = "detail for $trigger",
            ).toSealed()

            assertEquals(
                EventGatewayBranch(
                    id = "b-evt",
                    label = "Event fires",
                    triggerKind = trigger,
                    triggerDetail = "detail for $trigger",
                ),
                sealed,
            )
        }
    }

    @Test
    fun `SIGNAL trigger round-trips and requires its signal name`() {
        assertEquals(
            ContractTrigger.Signal(signalName = "market opened", description = "market opens"),
            FlatContractTrigger(
                type = FlatTriggerKind.SIGNAL,
                description = "market opens",
                signalName = "market opened",
            ).toSealed(),
        )

        val missing = FlatContractTrigger(type = FlatTriggerKind.SIGNAL, description = "market opens")
        val ex = assertFailsWith<IllegalArgumentException> { missing.toSealed() }
        assertTrue("signalName" in ex.message.orEmpty())
    }

    // An ESCALATION boundary event carries its escalation code in `detail`, exactly as a TIMER
    // carries its duration there; without one the generated escalation has nothing to reference.
    @Test
    fun `ESCALATION boundary event requires its escalation code`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            FlatContractBoundaryEvent(
                kind = BoundaryEventKind.ESCALATION,
                label = "approval overdue",
                detail = null,
            ).toSealed()
        }
        assertTrue("detail" in ex.message.orEmpty())

        val valid = FlatContractBoundaryEvent(
            kind = BoundaryEventKind.ESCALATION,
            label = "approval overdue",
            detail = "APPROVAL_OVERDUE",
        ).toSealed()
        assertEquals("APPROVAL_OVERDUE", valid.detail)
    }

    @Test
    fun `every FlatContractBranch kind round-trips to the matching sealed subtype`() {
        val conditional = FlatContractBranch(
            id = "b-c",
            label = "Eligible",
            kind = FlatBranchKind.CONDITIONAL,
            condition = "score >= 750",
        )
        assertEquals(
            ConditionalBranch(id = "b-c", label = "Eligible", condition = "score >= 750"),
            conditional.toSealed(),
        )

        val default = FlatContractBranch(id = "b-d", label = "Manual review", kind = FlatBranchKind.DEFAULT)
        assertEquals(DefaultBranch(id = "b-d", label = "Manual review"), default.toSealed())

        val unconditional =
            FlatContractBranch(id = "b-u", label = "IT prep", kind = FlatBranchKind.UNCONDITIONAL)
        assertEquals(UnconditionalBranch(id = "b-u", label = "IT prep"), unconditional.toSealed())

        val eventGateway = FlatContractBranch(
            id = "b-e",
            label = "Payment confirmed",
            kind = FlatBranchKind.EVENT_GATEWAY,
            triggerKind = EventTriggerKind.MESSAGE,
            triggerDetail = "payment confirmation",
        )
        assertEquals(
            EventGatewayBranch(
                id = "b-e",
                label = "Payment confirmed",
                triggerKind = EventTriggerKind.MESSAGE,
                triggerDetail = "payment confirmation",
            ),
            eventGateway.toSealed(),
        )
    }

    @Test
    fun `every FlatContractFlow shape round-trips to the matching sealed ContractFlow subtype`() {
        val sequence = FlatContractFlow(from = "start", to = "act-x", branchId = null)
        assertEquals(ContractFlow.Sequence(from = "start", to = "act-x"), sequence.toSealed())

        val branch = FlatContractFlow(from = "dec-x", to = "act-y", branchId = "br-yes")
        assertEquals(ContractFlow.Branch(from = "dec-x", to = "act-y", branchId = "br-yes"), branch.toSealed())
    }

    @Test
    fun `FlatProcessContract flows map through toSealed in order`() {
        val flat = FlatContractTestFixtures.minimalContract() as FlatProcessContract
        val withFlows = flat.copy(
            flows = listOf(
                FlatContractFlow(from = "start", to = "a1"),
                FlatContractFlow(from = "a1", to = "a2"),
                FlatContractFlow(from = "a2", to = "e1"),
            ),
        )
        assertEquals(
            listOf(
                ContractFlow.Sequence(from = "start", to = "a1"),
                ContractFlow.Sequence(from = "a1", to = "a2"),
                ContractFlow.Sequence(from = "a2", to = "e1"),
            ),
            withFlows.toSealed().flows,
        )
    }

    @Test
    fun `CONDITIONAL branch without condition fails with the offending id`() {
        val flat = FlatContractBranch(id = "b-c", label = "x", kind = FlatBranchKind.CONDITIONAL, condition = null)
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("b-c" in ex.message.orEmpty())
        assertTrue("condition" in ex.message.orEmpty())
    }

    @Test
    fun `EVENT_GATEWAY branch without triggerDetail fails with the offending id`() {
        val flat = FlatContractBranch(
            id = "b-e",
            label = "Payment confirmed",
            kind = FlatBranchKind.EVENT_GATEWAY,
            triggerKind = EventTriggerKind.MESSAGE,
            triggerDetail = null,
        )
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("b-e" in ex.message.orEmpty())
        assertTrue("triggerDetail" in ex.message.orEmpty())
    }

    @Test
    fun `every FlatContractTrigger kind round-trips to the matching sealed subtype`() {
        assertEquals(
            ContractTrigger.None("plain start"),
            FlatContractTrigger(type = FlatTriggerKind.NONE, description = "plain start").toSealed(),
        )
        assertEquals(
            ContractTrigger.Timer(BpmnTimerKind.CYCLE, "R/PT5M", "every 5m"),
            FlatContractTrigger(
                type = FlatTriggerKind.TIMER,
                description = "every 5m",
                timerKind = BpmnTimerKind.CYCLE,
                expression = "R/PT5M",
            ).toSealed(),
        )
        assertEquals(
            ContractTrigger.Message("order.submitted", "order webhook"),
            FlatContractTrigger(
                type = FlatTriggerKind.MESSAGE,
                description = "order webhook",
                messageName = "order.submitted",
            ).toSealed(),
        )
    }

    @Test
    fun `TIMER trigger without timerKind+expression fails`() {
        val noKind = FlatContractTrigger(
            type = FlatTriggerKind.TIMER,
            description = "x",
            timerKind = null,
            expression = "R/PT5M",
        )
        assertFailsWith<IllegalArgumentException> { noKind.toSealed() }

        val noExpr = FlatContractTrigger(
            type = FlatTriggerKind.TIMER,
            description = "x",
            timerKind = BpmnTimerKind.DATE,
            expression = null,
        )
        assertFailsWith<IllegalArgumentException> { noExpr.toSealed() }
    }

    @Test
    fun `FlatProcessContract toSealed composes per-element mappers and preserves siblings`() {
        val flat = FlatProcessContract(
            id = "c-1",
            processName = "Ship order",
            summary = "Approved orders are shipped.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "Order submitted",
                ),
                sourceIds = listOf("ev1"),
            ),
            activities = listOf(
                flatActivity(FlatActivityKind.USER, id = "a-pack"),
                flatActivity(FlatActivityKind.SERVICE, id = "a-ship"),
            ),
            decisions = listOf(
                FlatContractDecision(
                    id = "d-go",
                    question = "Approved?",
                    branches = listOf(
                        FlatContractBranch(
                            id = "b",
                            label = "yes",
                            kind = FlatBranchKind.CONDITIONAL,
                            condition = "approved",
                        ),
                    ),
                ),
            ),
            endStates = listOf(flatEnd(FlatEndStateKind.NORMAL, "e-ok")),
            throwEvents = listOf(flatThrow(FlatThrowEventKind.MESSAGE, "throw-msg", "invoice ready")),
            flows = listOf(
                FlatContractFlow(from = "a-ship", to = "throw-msg"),
                FlatContractFlow(from = "throw-msg", to = "e-ok"),
            ),
        )

        val sealed: ProcessContract = flat.toSealed()

        assertEquals("c-1", sealed.id)
        assertEquals(ContractStart(ContractTrigger.None("Order submitted"), listOf("ev1")), sealed.start)
        assertEquals(2, sealed.activities.size)
        assertTrue(sealed.activities[0] is ContractActivity.User)
        assertTrue(sealed.activities[1] is ContractActivity.Service)
        // Non-sealed siblings reused unchanged.
        assertEquals("d-go", sealed.decisions.single().id)
        assertTrue(sealed.endStates.single() is ContractEndState.Normal)
        assertTrue(sealed.intermediateThrows.single() is ContractIntermediateThrow.Message)
    }

    private fun flatActivity(
        kind: FlatActivityKind,
        id: String,
        decisionName: String? = null,
        messageName: String? = null,
        calledElement: String? = null,
    ): FlatContractActivity = FlatContractActivity(
        id = id,
        name = "Activity",
        kind = kind,
        actorId = null,
        sourceIds = listOf("ev1"),
        decisionName = decisionName,
        messageName = messageName,
        calledElement = calledElement,
    )

    private fun flatBoundaryEvent(
        kind: BoundaryEventKind,
        id: String,
        detail: String?,
    ): FlatContractBoundaryEvent = FlatContractBoundaryEvent(
        kind = kind,
        label = "Label",
        detail = detail,
        id = id,
    )

    private fun flatEnd(
        kind: FlatEndStateKind,
        id: String,
        payload: String? = null,
    ): FlatContractEndState = FlatContractEndState(
        id = id,
        name = "End",
        kind = kind,
        sourceIds = listOf("ev1"),
        errorCode = payload.takeIf { kind == FlatEndStateKind.ERROR },
    )

    private fun flatThrow(
        kind: FlatThrowEventKind,
        id: String,
        payload: String?,
    ): FlatContractThrowEvent = FlatContractThrowEvent(
        id = id,
        name = "Throw",
        kind = kind,
        sourceIds = listOf("ev1"),
        messageName = payload.takeIf { kind == FlatThrowEventKind.MESSAGE },
        signalName = payload.takeIf { kind == FlatThrowEventKind.SIGNAL },
        escalationCode = payload.takeIf { kind == FlatThrowEventKind.ESCALATION },
    )

    // Site 15: toPayloadActivity called with non-payload kind throws RetryableBpmnGenerationException.
    // The else branch is defensive — it handles LLM-emitted activity kinds that are not
    // payload kinds dispatched by toPayloadActivity.
    @Test
    fun `toPayloadActivity with non-payload kind throws RetryableBpmnGenerationException`() {
        // FlatActivityKind.SERVICE is a non-payload kind; toPayloadActivity's else branch fires.
        val activity = FlatContractActivity(
            id = "a1",
            name = "Do work",
            kind = FlatActivityKind.SERVICE,
            sourceIds = listOf("ev1"),
        )
        val ex = assertFailsWith<RetryableBpmnGenerationException> { activity.toPayloadActivity() }
        assertContains(ex.message!!, "toPayloadActivity called with non-payload kind")
        assertContains(ex.message!!, "SERVICE")
    }
}
