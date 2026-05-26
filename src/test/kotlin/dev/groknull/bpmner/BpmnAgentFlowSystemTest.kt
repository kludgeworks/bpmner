/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidator
import dev.groknull.bpmner.validation.LintIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers.any
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
    private lateinit var bpmnLintingPort: BpmnLintingPort

    @Test
    @Suppress("LongMethod")
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
            { it.contains("You are a BPMN alignment validator") },
            AlignmentFindings::class.java,
        ).thenReturn(
            AlignmentFindings(
                issues = emptyList(),
                rationale = "Aligned",
            ),
        )

        // 5. Mock Validators
        `when`(bpmnXsdValidator.validateDetailed(anyString()))
            .thenReturn(emptyList())
        doReturn(emptyList<LintIssue>())
            .`when`(bpmnLintingPort)
            .lint(anyDefinition())

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
        // BpmnLayoutAgent.autoFixBpmnXml stopped calling lint() in #243 (passthrough);
        // only BpmnEvaluationPipeline still invokes the rule engine once during evaluation.
        verify(bpmnLintingPort, times(1)).lint(anyDefinition())
    }

    private fun validDefinition() = BpmnDefinition(
        processId = "Process_MakeToast",
        processName = "Make Toast",
        nodes =
        listOf(
            BpmnStartEvent(id = "start", name = "Start"),
            BpmnUserTask(id = "task1", name = "Toast bread"),
            BpmnEndEvent(id = "end", name = "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(id = "f1", sourceRef = "start", targetRef = "task1"),
            BpmnEdge(id = "f2", sourceRef = "task1", targetRef = "end"),
        ),
    )

    private fun sampleContract(): ProcessContract {
        val sources = listOf("s1")
        return ProcessContract(
            id = "contract-1",
            processName = "Make Toast",
            summary = "Toast making process",
            trigger = "Hunger",
            triggerSourceIds = sources,
            activities =
            listOf(
                ContractActivity(id = "a1", name = "Get bread", sourceIds = sources),
                ContractActivity(id = "a2", name = "Toast bread", sourceIds = sources),
            ),
            endStates =
            listOf(
                ContractEndState(id = "e1", name = "Toast ready", sourceIds = sources),
            ),
        )
    }

    private fun anyDefinition(): BpmnDefinition = anyNonNull()

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }
}
