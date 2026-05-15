package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.core.AlignmentVerdict
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
import dev.groknull.bpmner.core.TraceLink
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Full end-to-end system test: validates the complete Embabel agent pipeline from BpmnRequest
 * through generation, repair, and layout to a written BPMN file. Per-module behavior is covered
 * by RepairModuleTest, GenerationModuleTest, and ValidationModuleTest.
 */
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
    ],
)
class BpmnAgentFlowSystemTest : EmbabelMockitoIntegrationTest() {
    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintService: BpmnLintService

    @Test
    fun `planner resolves request through definition render validation and write`(
        @TempDir tempDir: Path,
    ) {
        val definition = validDefinition()
        val outputFile = tempDir.resolve("process.bpmn")
        `when`(bpmnXsdValidator.validateDetailed(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintService)
            .lint(org.mockito.ArgumentMatchers.anyString(), eqPhase(BpmnLintPhase.SEMANTIC_PRE_LAYOUT))
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintService)
            .lint(org.mockito.ArgumentMatchers.anyString(), eqPhase(BpmnLintPhase.FINAL_POST_LAYOUT))
        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())
        whenCreateObject({ it.contains("Return only a structured ProcessContract object.") }, ProcessContract::class.java)
            .thenReturn(validContract())
        whenCreateObject({ it.contains("Generate a BPMN definition object") }, BpmnDefinition::class.java)
            .thenReturn(definition)
        whenCreateObject({ it.contains("Assess whether the generated BPMN process aligns semantically") }, BpmnAlignmentReport::class.java)
            .thenReturn(validAlignmentReport())

        val result =
            AgentPlatformTypedOps(agentPlatform)
                .transform(
                    BpmnRequest(
                        processDescription = "When an order is submitted, review it, then close it as completed.",
                        outputFile = outputFile.toString(),
                    ),
                    BpmnResult::class.java,
                    ProcessOptions(),
                )

        assertEquals(outputFile.toString(), result.outputFile)
        assertTrue(result.xml!!.contains("<process"))
        assertEquals(result.xml, outputFile.readText())
        verify(bpmnXsdValidator, times(2)).validateDetailed(org.mockito.ArgumentMatchers.anyString())
        verify(bpmnLintService, times(2)).lint(
            org.mockito.ArgumentMatchers.anyString(),
            eqPhase(BpmnLintPhase.SEMANTIC_PRE_LAYOUT),
        )
        verify(bpmnLintService).lint(
            org.mockito.ArgumentMatchers.anyString(),
            eqPhase(BpmnLintPhase.FINAL_POST_LAYOUT),
        )
    }

    private fun eqPhase(phase: BpmnLintPhase): BpmnLintPhase = org.mockito.ArgumentMatchers.eq(phase) ?: phase

    private fun validAssessment() =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 90,
            dimensions =
                listOf(
                    ReadinessDimensionScore(
                        dimension = ReadinessDimension.START_TRIGGER,
                        score = 90,
                        rationale = "Order submission is an explicit trigger.",
                    ),
                ),
            evidence =
                listOf(
                    SourceEvidence(
                        id = "ev1",
                        text = "When an order is submitted, review it, then close it as completed.",
                        sourceType = EvidenceSourceType.ORIGINAL_INPUT,
                    ),
                ),
            rationale = "The input contains a trigger, ordered activities, and an end state.",
        )

    private fun validContract() =
        ProcessContract(
            id = "contract-order",
            processName = "Handle order",
            summary = "Submitted orders are reviewed and closed.",
            trigger = "Order is submitted",
            triggerTraceLinks = listOf(trace("trigger")),
            activities =
                listOf(
                    ContractActivity(
                        id = "a-review",
                        name = "Review order",
                        traceLinks = listOf(trace("a-review")),
                    ),
                    ContractActivity(
                        id = "a-close",
                        name = "Close order",
                        traceLinks = listOf(trace("a-close")),
                    ),
                ),
            endStates =
                listOf(
                    ContractEndState(
                        id = "e-completed",
                        name = "Order completed",
                        traceLinks = listOf(trace("e-completed")),
                    ),
                ),
        )

    private fun validAlignmentReport() =
        BpmnAlignmentReport(
            verdict = AlignmentVerdict.ALIGNED,
            bpmnSummary = BpmnDefinitionSummary("Process_1", "Handle order", emptyList()),
            rationale = "Everything is aligned.",
        )

    private fun trace(targetId: String) =
        TraceLink(
            id = "trace-$targetId",
            sourceId = "ev1",
            targetId = targetId,
        )

    private fun validDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Handle order",
            nodes =
                listOf(
                    BpmnNode(
                        id = "StartEvent_1",
                        name = "Order submitted",
                        type = NodeType.START_EVENT,
                        bounds = BpmnBounds(100.0, 100.0, 36.0, 36.0),
                    ),
                    BpmnNode(
                        id = "Activity_1",
                        name = "Review order",
                        type = NodeType.USER_TASK,
                        bounds = BpmnBounds(200.0, 100.0, 100.0, 80.0),
                    ),
                    BpmnNode(
                        id = "Activity_2",
                        name = "Close order",
                        type = NodeType.USER_TASK,
                        bounds = BpmnBounds(400.0, 100.0, 100.0, 80.0),
                    ),
                    BpmnNode(
                        id = "EndEvent_1",
                        name = "Order completed",
                        type = NodeType.END_EVENT,
                        bounds = BpmnBounds(600.0, 100.0, 36.0, 36.0),
                    ),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        id = "Flow_1",
                        sourceRef = "StartEvent_1",
                        targetRef = "Activity_1",
                        waypoints =
                            listOf(
                                BpmnWaypoint(136.0, 118.0),
                                BpmnWaypoint(200.0, 118.0),
                            ),
                    ),
                    BpmnEdge(
                        id = "Flow_2",
                        sourceRef = "Activity_1",
                        targetRef = "Activity_2",
                        waypoints =
                            listOf(
                                BpmnWaypoint(300.0, 118.0),
                                BpmnWaypoint(400.0, 118.0),
                            ),
                    ),
                    BpmnEdge(
                        id = "Flow_3",
                        sourceRef = "Activity_2",
                        targetRef = "EndEvent_1",
                        waypoints =
                            listOf(
                                BpmnWaypoint(500.0, 118.0),
                                BpmnWaypoint(600.0, 118.0),
                            ),
                    ),
                ),
        )
}
