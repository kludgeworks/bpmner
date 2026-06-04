/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnContractAgentTest {
    @Test
    fun `extractProcessContract wraps a valid LLM response in a passing report`() {
        val context = FakeOperationContext()
        val flat = sampleFlatContract()
        context.expectResponse(flat)
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        val result = agent.extractProcessContract(sampleReadyContext(), context)

        assertEquals(flat.toSealed(), result.contract)
        assertTrue(result.isValid, "expected sample contract to pass validation, got ${result.report.issues}")
        assertEquals(1, context.llmInvocations.size)
    }

    @Test
    fun `extractProcessContract surfaces validation errors instead of swallowing them`() {
        val context = FakeOperationContext()
        // Trigger TRIGGER_WITHOUT_TRACE by dropping sourceIds from the start.
        val invalid = sampleFlatContract().copy(
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "An order is submitted",
                ),
            ),
        )
        context.expectResponse(invalid)
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        val result = agent.extractProcessContract(sampleReadyContext(), context)

        assertEquals(invalid.toSealed(), result.contract)
        assertTrue(!result.isValid)
        assertTrue(result.report.issues.any { it.code.name == "TRIGGER_WITHOUT_TRACE" })
    }

    @Test
    fun `prompt sent to the LLM grounds the model in the supplied inputs`() {
        val context = FakeOperationContext()
        context.expectResponse(sampleFlatContract())
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        agent.extractProcessContract(sampleReadyContext(), context)

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("When a customer submits an order, ship it."))
        assertTrue(prompt.contains("One actor responsibility is underspecified."))
        assertTrue(prompt.contains("ev1: Ship approved order"))
        assertTrue(prompt.contains("Do not invent actors"))
    }

    @Test
    fun `prompt sent to the LLM includes request clarification history`() {
        val context = FakeOperationContext()
        context.expectResponse(sampleFlatContract())
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        agent.extractProcessContract(
            sampleReadyContext(
                request =
                sampleRequest().copy(
                    clarificationHistory =
                    listOf(
                        ClarificationExchange(
                            questionId = "q1",
                            questionText = "What starts the process?",
                            answerText = "The customer submits an order.",
                        ),
                    ),
                ),
            ),
            context,
        )

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("[q1] Q: What starts the process?"))
        assertTrue(prompt.contains("A: The customer submits an order."))
    }

    private fun sampleRequest() = BpmnRequest(processDescription = "When a customer submits an order, ship it.")

    private fun sampleReadyContext(
        request: BpmnRequest = sampleRequest(),
        assessment: ProcessInputAssessment = sampleAssessment(),
    ) = ReadyBpmnContext(request = request, assessment = assessment)

    private fun sampleAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
        overallScore = 60,
        dimensions = emptyList(),
        evidence =
        listOf(
            SourceEvidence(
                id = "ev1",
                text = "Ship approved order",
                sourceType = EvidenceSourceType.ORIGINAL_INPUT,
            ),
        ),
        rationale = "One actor responsibility is underspecified.",
    )

    private fun sampleFlatContract(): FlatProcessContract {
        val sources = listOf("ev1")
        return FlatProcessContract(
            id = "contract-1",
            processName = "Ship order",
            summary = "Approved orders are packed and shipped.",
            start = FlatContractStart(
                trigger = FlatContractTrigger(
                    type = FlatTriggerKind.NONE,
                    description = "An order is submitted",
                ),
                sourceIds = sources,
            ),
            activities = listOf(
                FlatContractActivity(
                    id = "a-pack",
                    name = "Pack order",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = sources,
                ),
                FlatContractActivity(
                    id = "a-ship",
                    name = "Ship order",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = sources,
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = "end-shipped",
                    name = "Order shipped",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = sources,
                ),
            ),
        )
    }
}
