/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.anyString
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

        // 1. Mock Readiness
        whenCreateObject(
            { it.contains("Assess whether the source text describes a workflow") },
            ProcessInputAssessment::class.java,
        ).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 100,
                dimensions = emptyList(),
                evidence = emptyList(),
                rationale = "Ready",
            ),
        )

        // 2. Mock Contract
        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            ProcessContract::class.java,
        ).thenReturn(sampleContract())

        // 3. Mock Generation
        whenCreateObject(
            { it.contains("Generate a BPMN definition object from the validated process contract") },
            BpmnDefinition::class.java,
        ).thenReturn(definition)

        // 4. Mock Alignment
        whenCreateObject(
            { it.contains("Assess whether generated BPMN aligns semantically") },
            BpmnAlignmentReport::class.java,
        ).thenReturn(
            BpmnAlignmentReport(
                verdict = AlignmentVerdict.ALIGNED,
                bpmnSummary =
                    dev.groknull.bpmner.alignment.BpmnDefinitionSummary(
                        definition.processId,
                        definition.processName,
                        emptyList(),
                    ),
                rationale = "Aligned",
            ),
        )

        // 5. Mock Validators
        `when`(bpmnXsdValidator.validateDetailed(anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintService)
            .lint(anyString(), eqPhase(BpmnLintPhase.SEMANTIC_PRE_LAYOUT))
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintService)
            .lint(anyString(), eqPhase(BpmnLintPhase.FINAL_POST_LAYOUT))

        val result =
            AgentPlatformTypedOps(agentPlatform)
                .transform(
                    BpmnRequest(
                        processDescription = "Make toast",
                        outputFile = outputFile.toString(),
                    ),
                    BpmnResult::class.java,
                    ProcessOptions(),
                )

        assertEquals(outputFile.toString(), result.outputFile)
        assertTrue(result.xml!!.contains("<process"))
        assertEquals(result.xml, outputFile.readText())
        verify(bpmnXsdValidator, times(2)).validateDetailed(anyString())
        verify(bpmnLintService, times(2)).lint(
            anyString(),
            eqPhase(BpmnLintPhase.SEMANTIC_PRE_LAYOUT),
        )
        verify(bpmnLintService).lint(
            anyString(),
            eqPhase(BpmnLintPhase.FINAL_POST_LAYOUT),
        )
    }

    private fun eqPhase(phase: BpmnLintPhase): BpmnLintPhase = org.mockito.ArgumentMatchers.eq(phase) ?: phase

    private fun validDefinition() =
        BpmnDefinition(
            processId = "Process_MakeToast",
            processName = "Make Toast",
            nodes =
                listOf(
                    BpmnNode(id = "start", name = "Start", type = NodeType.START_EVENT, bounds = BpmnBounds(0.0, 0.0, 36.0, 36.0)),
                    BpmnNode(id = "task1", name = "Toast bread", type = NodeType.USER_TASK, bounds = BpmnBounds(100.0, 0.0, 100.0, 80.0)),
                    BpmnNode(id = "end", name = "End", type = NodeType.END_EVENT, bounds = BpmnBounds(300.0, 0.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        id = "f1",
                        sourceRef = "start",
                        targetRef = "task1",
                        waypoints = listOf(BpmnWaypoint(36.0, 18.0), BpmnWaypoint(100.0, 40.0)),
                    ),
                    BpmnEdge(
                        id = "f2",
                        sourceRef = "task1",
                        targetRef = "end",
                        waypoints = listOf(BpmnWaypoint(200.0, 40.0), BpmnWaypoint(300.0, 18.0)),
                    ),
                ),
        )

    private fun sampleContract(): ProcessContract {
        val trace =
            TraceLink(
                id = "trace-1",
                sourceId = "s1",
                targetId = "t1",
                classification = AlignmentClassification.SUPPORTED,
            )
        return ProcessContract(
            id = "contract-1",
            processName = "Make Toast",
            summary = "Toast making process",
            trigger = "Hunger",
            triggerTraceLinks = listOf(trace),
            activities =
                listOf(
                    ContractActivity(id = "a1", name = "Get bread", traceLinks = listOf(trace)),
                    ContractActivity(id = "a2", name = "Toast bread", traceLinks = listOf(trace)),
                ),
            endStates =
                listOf(
                    ContractEndState(id = "e1", name = "Toast ready", traceLinks = listOf(trace)),
                ),
        )
    }
}
