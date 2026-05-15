package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import dev.groknull.bpmner.contract.internal.domain.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnContractAgentTest {
    @Test
    fun `extractProcessContract wraps a valid LLM response in a passing report`() {
        val context = FakeOperationContext()
        val expected = sampleContract()
        context.expectResponse(expected)
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        val result = agent.extractProcessContract(sampleRequest(), sampleAssessment(), context)

        assertEquals(expected, result.contract)
        assertTrue(result.isValid, "expected sample contract to pass validation, got ${result.report.issues}")
        assertEquals(1, context.llmInvocations.size)
    }

    @Test
    fun `extractProcessContract surfaces validation errors instead of swallowing them`() {
        val context = FakeOperationContext()
        val invalid = sampleContract().copy(triggerTraceLinks = emptyList())
        context.expectResponse(invalid)
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        val result = agent.extractProcessContract(sampleRequest(), sampleAssessment(), context)

        assertEquals(invalid, result.contract)
        assertTrue(!result.isValid)
        assertTrue(result.report.issues.any { it.code.name == "TRIGGER_WITHOUT_TRACE" })
    }

    @Test
    fun `prompt sent to the LLM grounds the model in the supplied inputs`() {
        val context = FakeOperationContext()
        context.expectResponse(sampleContract())
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        agent.extractProcessContract(sampleRequest(), sampleAssessment(), context)

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("When a customer submits an order, ship it."))
        assertTrue(prompt.contains("One actor responsibility is underspecified."))
        assertTrue(prompt.contains("ev1: Ship approved order"))
        assertTrue(prompt.contains("Do not invent actors"))
    }

    @Test
    fun `prompt sent to the LLM includes request clarification history`() {
        val context = FakeOperationContext()
        context.expectResponse(sampleContract())
        val agent = BpmnContractAgent(BpmnConfig(), BpmnContractValidator(), ProcessContractMarkdownRenderer())

        agent.extractProcessContract(
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
            sampleAssessment(),
            context,
        )

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("[q1] Q: What starts the process?"))
        assertTrue(prompt.contains("A: The customer submits an order."))
    }

    private fun sampleRequest() = BpmnRequest(processDescription = "When a customer submits an order, ship it.")

    private fun sampleAssessment() =
        ProcessInputAssessment(
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

    private fun sampleContract(): ProcessContract {
        val trace =
            TraceLink(
                id = "trace-ev1",
                sourceId = "ev1",
                targetId = "self",
                classification = AlignmentClassification.SUPPORTED,
            )
        return ProcessContract(
            id = "contract-1",
            processName = "Ship order",
            summary = "Approved orders are packed and shipped.",
            trigger = "An order is submitted",
            triggerTraceLinks = listOf(trace),
            activities =
                listOf(
                    ContractActivity(id = "a-pack", name = "Pack order", traceLinks = listOf(trace)),
                    ContractActivity(id = "a-ship", name = "Ship order", traceLinks = listOf(trace)),
                ),
            endStates =
                listOf(
                    ContractEndState(id = "end-shipped", name = "Order shipped", traceLinks = listOf(trace)),
                ),
        )
    }
}
