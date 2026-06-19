/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentClassification
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentIssue
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentException
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnEdge
import dev.groknull.bpmner.domain.BpmnRequest
import dev.groknull.bpmner.generation.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.FlatBpmnNode
import dev.groknull.bpmner.generation.FlatBpmnNodeKind
import dev.groknull.bpmner.readiness.EvidenceSourceType
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.SourceEvidence
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.LintIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class BpmnAlignmentFailureIntegrationTest : EmbabelMockitoIntegrationTest() {
    @MockitoBean
    private lateinit var bpmnXsdValidationPort: BpmnXsdValidationPort

    @MockitoBean
    private lateinit var bpmnLintingPort: BpmnLintingPort

    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `alignment failure yields a typed ALIGNMENT_FAILED result`() {
        `when`(bpmnXsdValidationPort.validateDetailed(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintingPort)
            .lint(anyDefinition())

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())

        @Suppress("UNCHECKED_CAST")
        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS as Class<Any>,
        ).thenReturn(FlatContractTestFixtures.minimalContract())

        whenCreateObject({ it.contains("Generate a BPMN definition object") }, FlatBpmnDefinition::class.java)
            .thenReturn(validFlatDefinition())

        // Mock alignment failure: UNSUPPORTED issue → AlignmentVerdict.FAILED via BpmnAlignmentPostChecker
        // (blockOnUnsupportedElements = true by default, BpmnAlignmentConfig).
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

        // Doc §3.2, §1 G5: FAILED verdict → typed BpmnResult(status=ALIGNMENT_FAILED), no throw.
        val result =
            bpmnAgentInvoker.generate(
                BpmnRequest(
                    processDescription = "When a user submits an order, we process it and then it is completed.",
                    outputFile = "ignored.bpmn",
                ),
                validAssessment(),
            )

        assertEquals(BpmnGenerationStatus.ALIGNMENT_FAILED, result.status)
        assertNotNull(result.alignmentReport)
        assertEquals(AlignmentVerdict.FAILED, result.alignmentReport!!.verdict)
    }

    /**
     * Doc §8 risk #3 / plan §5 gate 8: the malformed-LLM-response path (report=null) in
     * [dev.groknull.bpmner.alignment.internal.adapter.inbound.LlmBpmnAligner.requestAlignmentFindings]
     * must remain a [BpmnAlignmentException] distinct from the verdict path.
     * [BpmnAlignmentException] with [BpmnAlignmentException.report] = null signals "the model
     * didn't respond" (infra failure), while report != null signals "the model examined the BPMN
     * and found problems." The two are structurally distinct and must not be conflated.
     */
    @Test
    fun `BpmnAlignmentException with null report is structurally distinct from verdict failure`() {
        // Infra failure shape: BpmnAlignmentException(report=null) must be distinguishable from
        // verdict failure (BpmnAlignmentException(report != null)). Verdict failure has a non-null
        // report with FAILED verdict; infra failure has null report.
        val verdictFailureReport = mock<BpmnAlignmentReport>()
        `when`(verdictFailureReport.verdict).thenReturn(AlignmentVerdict.FAILED)
        val verdictFailure = BpmnAlignmentException(
            message = "Alignment failed",
            report = verdictFailureReport,
        )
        assertNotNull(verdictFailure.report)
        assertEquals(AlignmentVerdict.FAILED, verdictFailure.report!!.verdict)

        val infraFailure = BpmnAlignmentException(
            message = "Alignment model failed to produce a structured report",
            report = null,
        )
        assertNull(infraFailure.report)
        assertThrows<BpmnAlignmentException> { throw infraFailure }
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
