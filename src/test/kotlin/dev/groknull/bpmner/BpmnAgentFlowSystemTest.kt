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
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatActivityKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractActivity
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractEndState
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractStart
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractTrigger
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatEndStateKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatTriggerKind
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.FlatBpmnNode
import dev.groknull.bpmner.generation.FlatBpmnNodeKind
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
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
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
        val flatDefinition = validFlatDefinition()
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
            FlatProcessContract::class.java,
        ).thenReturn(sampleFlatContract())

        // 3. Mock Generation
        whenCreateObject(
            { it.contains("Generate a BPMN definition object from the validated process contract") },
            FlatBpmnDefinition::class.java,
        ).thenReturn(flatDefinition)

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
                    // Mirrors `AgentPlatformBpmnAgentInvoker.syncGenerationProcessOptions()`:
                    // exercise the real budget so a regression that pushes generation past
                    // 100 actions surfaces here rather than only in production.
                    ProcessOptions(budget = Budget(actions = 100), ephemeral = true),
                )

        assertEquals(outputFile.toString(), result.outputFile)
        assertTrue(result.xml!!.contains("<process"))
        assertEquals(result.xml, outputFile.readText())
        verify(bpmnXsdValidator, times(2)).validateDetailed(anyString())
        // BpmnLayoutAgent.autoFixBpmnXml stopped calling lint() in #243 (passthrough);
        // only BpmnEvaluationPipeline still invokes the rule engine once during evaluation.
        verify(bpmnLintingPort, times(1)).lint(anyDefinition())
    }

    private fun validFlatDefinition() = FlatBpmnDefinition(
        processId = "Process_MakeToast",
        processName = "Make Toast",
        nodes = listOf(
            FlatBpmnNode(id = "start", type = FlatBpmnNodeKind.START_EVENT, name = "Start"),
            FlatBpmnNode(id = "task1", type = FlatBpmnNodeKind.USER_TASK, name = "Toast bread"),
            FlatBpmnNode(id = "end", type = FlatBpmnNodeKind.END_EVENT, name = "End"),
        ),
        sequences = listOf(
            BpmnEdge(id = "f1", sourceRef = "start", targetRef = "task1"),
            BpmnEdge(id = "f2", sourceRef = "task1", targetRef = "end"),
        ),
    )

    private fun sampleFlatContract(): FlatProcessContract {
        val sources = listOf("s1")
        return FlatProcessContract(
            id = "contract-1",
            processName = "Make Toast",
            summary = "Toast making process",
            start = FlatContractStart(
                trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Hunger"),
                sourceIds = sources,
            ),
            activities = listOf(
                FlatContractActivity(
                    id = "a1",
                    name = "Get bread",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = sources,
                ),
                FlatContractActivity(
                    id = "a2",
                    name = "Toast bread",
                    kind = FlatActivityKind.SERVICE,
                    sourceIds = sources,
                ),
            ),
            endStates = listOf(
                FlatContractEndState(
                    id = "e1",
                    name = "Toast ready",
                    kind = FlatEndStateKind.NORMAL,
                    sourceIds = sources,
                ),
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
