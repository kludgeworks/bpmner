package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.LintIssue
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
        whenCreateObject(
            { it.contains("Assess whether generated BPMN aligns semantically with process contract") },
            BpmnAlignmentReport::class.java,
        ).thenReturn(validAlignmentReport())

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
                    node("StartEvent_1", "Order submitted", NodeType.START_EVENT, 100.0),
                    node("Activity_1", "Review order", NodeType.USER_TASK, 200.0),
                    node("Activity_2", "Close order", NodeType.USER_TASK, 400.0),
                    node("EndEvent_1", "Order completed", NodeType.END_EVENT, 600.0),
                ),
            sequences =
                listOf(
                    edge("Flow_1", "StartEvent_1", "Activity_1", 136.0, 200.0),
                    edge("Flow_2", "Activity_1", "Activity_2", 300.0, 400.0),
                    edge("Flow_3", "Activity_2", "EndEvent_1", 500.0, 600.0),
                ),
        )

    private fun node(
        id: String,
        name: String,
        type: NodeType,
        x: Double,
    ): BpmnNode {
        val (w, h) =
            when (type) {
                NodeType.START_EVENT, NodeType.END_EVENT -> 36.0 to 36.0
                else -> 100.0 to 80.0
            }
        return BpmnNode(id = id, name = name, type = type, bounds = BpmnBounds(x, 100.0, w, h))
    }

    private fun edge(
        id: String,
        source: String,
        target: String,
        x1: Double,
        x2: Double,
    ) = BpmnEdge(
        id = id,
        sourceRef = source,
        targetRef = target,
        waypoints = listOf(BpmnWaypoint(x1, 118.0), BpmnWaypoint(x2, 118.0)),
    )
}
