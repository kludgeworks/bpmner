package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.core.AlignedElement
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.AlignmentVerdict
import dev.groknull.bpmner.core.BpmnAlignmentException
import dev.groknull.bpmner.core.BpmnAlignmentReport
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDefinitionSummary
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ContractActivity
import dev.groknull.bpmner.core.ContractEndState
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.ProcessContract
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.ReadinessDimensionScore
import dev.groknull.bpmner.core.ReadinessVerdict
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
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
    ],
)
class BpmnAlignmentFailureIntegrationTest : EmbabelMockitoIntegrationTest() {
    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintService: BpmnLintService

    @Test
    fun `alignment failure blocks the pipeline`() {
        `when`(bpmnXsdValidator.validateDetailed(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintService)
            .lint(org.mockito.ArgumentMatchers.anyString(), anyPhase())

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())
        whenCreateObject({ it.contains("Return only a structured ProcessContract object.") }, ProcessContract::class.java)
            .thenReturn(validContract())
        whenCreateObject({ it.contains("Generate a BPMN definition object") }, BpmnDefinition::class.java)
            .thenReturn(validDefinition())

        // Mock alignment failure
        whenCreateObject({ true }, BpmnAlignmentReport::class.java)
            .thenReturn(
                BpmnAlignmentReport(
                    verdict = AlignmentVerdict.FAILED,
                    bpmnSummary = BpmnDefinitionSummary("Process_1", "Dummy", emptyList()),
                    rationale = "Generated process is completely unrelated to the contract.",
                    alignedElements =
                        listOf(
                            AlignedElement(
                                id = "unsupported_task",
                                classification = AlignmentClassification.UNSUPPORTED,
                                rationale = "Not in contract",
                            ),
                        ),
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
                        ProcessOptions(),
                    )
            }

        assertTrue(error.message!!.contains("Generated BPMN does not align with process contract"))
        assertEquals(AlignmentVerdict.FAILED, error.report.verdict)
    }

    private fun validAssessment() =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 90,
            dimensions =
                listOf(
                    ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK"),
                ),
            evidence = listOf(SourceEvidence("ev1", "Unused", EvidenceSourceType.ORIGINAL_INPUT)),
            rationale = "Ready",
        )

    private fun validContract() =
        ProcessContract(
            id = "contract-1",
            processName = "Dummy",
            summary = "Summary",
            trigger = "Trigger",
            triggerTraceLinks = listOf(trace("trigger")),
            activities =
                listOf(
                    ContractActivity("a1", "A1", traceLinks = listOf(trace("a1"))),
                    ContractActivity("a2", "A2", traceLinks = listOf(trace("a2"))),
                ),
            endStates =
                listOf(
                    ContractEndState("e1", "E1", traceLinks = listOf(trace("e1"))),
                ),
        )

    private fun validDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Dummy",
            nodes =
                listOf(
                    BpmnNode("start", "Start", NodeType.START_EVENT, BpmnBounds(100.0, 100.0, 36.0, 36.0)),
                    BpmnNode("end", "End", NodeType.END_EVENT, BpmnBounds(500.0, 100.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge("flow1", "start", "end", waypoints = listOf(BpmnWaypoint(136.0, 118.0), BpmnWaypoint(500.0, 118.0))),
                ),
        )

    private fun trace(targetId: String) =
        dev.groknull.bpmner.core
            .TraceLink("trace-$targetId", "ev1", targetId)

    private fun anyPhase(): BpmnLintPhase = org.mockito.ArgumentMatchers.any() ?: BpmnLintPhase.SEMANTIC_PRE_LAYOUT
}
