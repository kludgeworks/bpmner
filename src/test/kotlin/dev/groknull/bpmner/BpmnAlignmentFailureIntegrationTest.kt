/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentIssue
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatActivityKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractActivity
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractEndState
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractStart
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractTrigger
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatEndStateKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatTriggerKind
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.FlatBpmnNode
import dev.groknull.bpmner.generation.FlatBpmnNodeKind
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidator
import dev.groknull.bpmner.validation.LintIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.`when`
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
    ],
)
class BpmnAlignmentFailureIntegrationTest : EmbabelMockitoIntegrationTest() {
    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintingPort: BpmnLintingPort

    @Test
    fun `alignment failure blocks the pipeline`() {
        `when`(bpmnXsdValidator.validateDetailed(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintingPort)
            .lint(anyDefinition())

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())
        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            FlatProcessContract::class.java,
        ).thenReturn(validFlatContract())
        whenCreateObject({ it.contains("Generate a BPMN definition object") }, FlatBpmnDefinition::class.java)
            .thenReturn(validFlatDefinition())

        // Mock alignment failure
        whenCreateObject({ true }, AlignmentFindings::class.java)
            .thenReturn(
                AlignmentFindings(
                    issues =
                    listOf(
                        AlignmentIssue(
                            elementId = "unsupported_task",
                            classification = AlignmentClassification.UNSUPPORTED,
                        ),
                    ),
                    rationale = "Generated process is completely unrelated to the contract.",
                ),
            )

        val error =
            assertThrows<BpmnAlignmentException> {
                AgentPlatformTypedOps(agentPlatform)
                    .transform(
                        BpmnRequest(
                            processDescription = "Unused",
                            outputFile = "ignored.bpmn",
                        ),
                        BpmnResult::class.java,
                        // Mirrors `AgentPlatformBpmnAgentInvoker.syncGenerationProcessOptions()`
                        // so the test exercises the real budget.
                        ProcessOptions(budget = Budget(actions = 100), ephemeral = true),
                    )
            }

        assertTrue(error.message!!.contains("Generated BPMN does not align with process contract"))
        // `report` is non-null on the "FAILED verdict" path; null is only used when the alignment
        // model itself failed to produce a structured response (a separate test would cover that).
        assertEquals(AlignmentVerdict.FAILED, error.report!!.verdict)
    }

    private fun anyDefinition(): BpmnDefinition = anyNonNull()

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun validAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions =
        listOf(
            ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK"),
        ),
        evidence = listOf(SourceEvidence("ev1", "Unused", EvidenceSourceType.ORIGINAL_INPUT)),
        rationale = "Ready",
    )

    private fun validFlatContract() = FlatProcessContract(
        id = "contract-1",
        processName = "Dummy",
        summary = "Summary",
        start = FlatContractStart(
            trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Trigger"),
            sourceIds = listOf("ev1"),
        ),
        activities = listOf(
            FlatContractActivity(id = "a1", name = "A1", kind = FlatActivityKind.SERVICE, sourceIds = listOf("ev1")),
            FlatContractActivity(id = "a2", name = "A2", kind = FlatActivityKind.SERVICE, sourceIds = listOf("ev1")),
        ),
        endStates = listOf(
            FlatContractEndState(id = "e1", name = "E1", kind = FlatEndStateKind.NORMAL, sourceIds = listOf("ev1")),
        ),
    )

    private fun validFlatDefinition() = FlatBpmnDefinition(
        processId = "Process_1",
        processName = "Dummy",
        nodes = listOf(
            FlatBpmnNode(id = "start", type = FlatBpmnNodeKind.START_EVENT, name = "Start"),
            FlatBpmnNode(id = "end", type = FlatBpmnNodeKind.END_EVENT, name = "End"),
        ),
        sequences = listOf(BpmnEdge(id = "flow1", sourceRef = "start", targetRef = "end")),
    )
}
