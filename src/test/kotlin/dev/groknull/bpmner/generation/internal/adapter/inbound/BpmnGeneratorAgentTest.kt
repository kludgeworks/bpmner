/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.ContractValidationIssue
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.internal.domain.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.internal.domain.DefaultFlowAssigner
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnGeneratorAgentTest {
    @Test
    fun `createProcessOutline sends a contract-first prompt and returns outline metrics`() {
        val context = FakeOperationContext()
        val definition = testBpmnDefinition(processName = "Handle claim")
        context.expectResponse(definition)
        val agent = agent()

        val outline =
            agent.createProcessOutline(
                BpmnRequest(
                    processDescription = "Raw prose kept for traceability.",
                    styleGuide = "Use sentence case names.",
                ),
                validContract(),
                context,
            )

        assertEquals(definition, outline.definition)
        assertEquals(1, outline.metrics.phaseCount)
        assertEquals(1, context.llmInvocations.size)
        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("Primary validated ProcessContract:"))
        assertTrue(prompt.contains("Handle claim"))
        assertTrue(prompt.contains("Original input for traceability only:"))
        assertTrue(prompt.contains("Raw prose kept for traceability."))
        assertTrue(
            prompt.indexOf("Primary validated ProcessContract:") <
                prompt.indexOf("Original input for traceability only:"),
        )
    }

    @Test
    fun `createProcessOutline fails before LLM generation for invalid contracts`() {
        val context = FakeOperationContext()
        val agent = agent()
        val invalid =
            ValidatedProcessContract(
                contract = validContract().contract,
                report =
                ContractValidationReport(
                    listOf(
                        ContractValidationIssue(
                            code = ContractValidationCode.INSUFFICIENT_ACTIVITIES,
                            severity = ContractIssueSeverity.ERROR,
                            message = "At least two activities are required",
                            targetId = "contract-claim",
                        ),
                    ),
                ),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                agent.createProcessOutline(BpmnRequest("Generate this"), invalid, context)
            }

        assertTrue(error.message.orEmpty().contains("invalid process contract"))
        assertTrue(error.message.orEmpty().contains("insufficient_activities"))
        assertTrue(context.llmInvocations.isEmpty())
    }

    private fun agent() = BpmnGeneratorAgent(
        config = BpmnConfig(),
        bpmnConverter = NoopRenderer,
        metricsCalculator = BpmnGeneratorMetrics(),
        fidelityChecker = BpmnContractFidelityChecker(),
        defaultFlowAssigner = DefaultFlowAssigner(),
        eventPublisher = ApplicationEventPublisher {},
        contractRenderer = ProcessContractMarkdownRenderer(),
    )

    private fun validContract(): ValidatedProcessContract {
        val sources = listOf("ev1")
        return ValidatedProcessContract(
            contract =
            ProcessContract(
                id = "contract-claim",
                processName = "Handle claim",
                summary = "Claims are reviewed and closed.",
                trigger = "Claim is submitted",
                triggerSourceIds = sources,
                activities =
                listOf(
                    ContractActivity(
                        id = "a-review",
                        name = "Review claim",
                        sourceIds = sources,
                    ),
                    ContractActivity(
                        id = "a-close",
                        name = "Close claim",
                        sourceIds = sources,
                    ),
                ),
                endStates =
                listOf(
                    ContractEndState(
                        id = "end-done",
                        name = "Claim closed",
                        sourceIds = sources,
                    ),
                ),
            ),
            report = ContractValidationReport(emptyList()),
        )
    }

    private object NoopRenderer : BpmnRenderer {
        override fun render(definition: BpmnDefinition): RenderedBpmn = RenderedBpmn(
            definition = definition,
            xml = "<definitions />",
            elementIndex =
            BpmnElementIndex(
                processId = definition.processId,
                nodeObjectRefs = emptyMap(),
                edgeObjectRefs = emptyMap(),
            ),
        )

        override fun render(graph: LaidOutProcessGraph): RenderedBpmn = render(graph.definition)
    }
}
