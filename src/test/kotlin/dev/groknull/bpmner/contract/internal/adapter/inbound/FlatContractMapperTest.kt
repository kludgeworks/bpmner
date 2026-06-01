/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import kotlin.test.Test
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
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
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
    fun `every FlatContractEndState kind round-trips to the matching sealed subtype`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractEndState, ContractEndState>> = listOf(
            flatEnd(FlatEndStateKind.NORMAL, "e-norm") to
                ContractEndState.Normal("e-norm", "End", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.TERMINATE, "e-term") to
                ContractEndState.Terminate("e-term", "End", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.ERROR, "e-err", payload = "CREDIT_REJECTED") to
                ContractEndState.Error("e-err", "End", errorCode = "CREDIT_REJECTED", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.MESSAGE, "e-msg", payload = "shipped") to
                ContractEndState.Message("e-msg", "End", messageName = "shipped", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.SIGNAL, "e-sig", payload = "settled") to
                ContractEndState.Signal("e-sig", "End", signalName = "settled", sourceIds = sourceIds),
            flatEnd(FlatEndStateKind.ESCALATION, "e-esc", payload = "OVERDUE") to
                ContractEndState.Escalation(
                    "e-esc",
                    "End",
                    escalationCode = "OVERDUE",
                    sourceIds = sourceIds,
                ),
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
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

    @Test
    fun `every FlatContractIntermediateThrow kind round-trips to the matching sealed subtype`() {
        val sourceIds = listOf("ev1")
        val cases: List<Pair<FlatContractIntermediateThrow, ContractIntermediateThrow>> = listOf(
            flatThrow(FlatIntermediateThrowKind.MESSAGE, "throw-msg", payload = "invoice ready") to
                ContractIntermediateThrow.Message(
                    "throw-msg",
                    "Throw",
                    messageName = "invoice ready",
                    sourceIds = sourceIds,
                ),
            flatThrow(FlatIntermediateThrowKind.SIGNAL, "throw-sig", payload = "stock changed") to
                ContractIntermediateThrow.Signal(
                    "throw-sig",
                    "Throw",
                    signalName = "stock changed",
                    sourceIds = sourceIds,
                ),
            flatThrow(FlatIntermediateThrowKind.ESCALATION, "throw-esc", payload = "APPROVAL_OVERDUE") to
                ContractIntermediateThrow.Escalation(
                    "throw-esc",
                    "Throw",
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = sourceIds,
                ),
        )

        cases.forEach { (flat, expected) -> assertEquals(expected, flat.toSealed()) }
    }

    @Test
    fun `FlatContractIntermediateThrow required payload fields fail with offending id`() {
        val message = flatThrow(FlatIntermediateThrowKind.MESSAGE, "throw-msg", payload = null)
        val messageEx = assertFailsWith<IllegalArgumentException> { message.toSealed() }
        assertTrue("throw-msg" in messageEx.message.orEmpty())
        assertTrue("messageName" in messageEx.message.orEmpty())

        val signal = flatThrow(FlatIntermediateThrowKind.SIGNAL, "throw-sig", payload = " ")
        val signalEx = assertFailsWith<IllegalArgumentException> { signal.toSealed() }
        assertTrue("throw-sig" in signalEx.message.orEmpty())
        assertTrue("signalName" in signalEx.message.orEmpty())

        val escalation = flatThrow(FlatIntermediateThrowKind.ESCALATION, "throw-esc", payload = "")
        val escalationEx = assertFailsWith<IllegalArgumentException> { escalation.toSealed() }
        assertTrue("throw-esc" in escalationEx.message.orEmpty())
        assertTrue("escalationCode" in escalationEx.message.orEmpty())
    }

    @Test
    fun `every FlatContractBranch kind round-trips to the matching sealed subtype`() {
        val conditional = FlatContractBranch(
            id = "b-c",
            label = "Eligible",
            kind = FlatBranchKind.CONDITIONAL,
            condition = "score >= 750",
            nextRef = null,
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
    }

    @Test
    fun `CONDITIONAL branch without condition fails with the offending id`() {
        val flat = FlatContractBranch(id = "b-c", label = "x", kind = FlatBranchKind.CONDITIONAL, condition = null)
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("b-c" in ex.message.orEmpty())
        assertTrue("condition" in ex.message.orEmpty())
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
        assertEquals(
            ContractTrigger.Signal("settlement", "broadcast"),
            FlatContractTrigger(
                type = FlatTriggerKind.SIGNAL,
                description = "broadcast",
                signalName = "settlement",
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
            intermediateThrows = listOf(flatThrow(FlatIntermediateThrowKind.MESSAGE, "throw-msg", "invoice ready")),
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
    ): FlatContractActivity = FlatContractActivity(
        id = id,
        name = "Activity",
        kind = kind,
        actorId = null,
        sourceIds = listOf("ev1"),
        decisionName = decisionName,
        messageName = messageName,
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
        messageName = payload.takeIf { kind == FlatEndStateKind.MESSAGE },
        signalName = payload.takeIf { kind == FlatEndStateKind.SIGNAL },
        escalationCode = payload.takeIf { kind == FlatEndStateKind.ESCALATION },
    )

    private fun flatThrow(
        kind: FlatIntermediateThrowKind,
        id: String,
        payload: String?,
    ): FlatContractIntermediateThrow = FlatContractIntermediateThrow(
        id = id,
        name = "Throw",
        kind = kind,
        sourceIds = listOf("ev1"),
        messageName = payload.takeIf { kind == FlatIntermediateThrowKind.MESSAGE },
        signalName = payload.takeIf { kind == FlatIntermediateThrowKind.SIGNAL },
        escalationCode = payload.takeIf { kind == FlatIntermediateThrowKind.ESCALATION },
    )
}
