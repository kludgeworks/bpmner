/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentIssue
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
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidator
import dev.groknull.bpmner.validation.LintIssue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.file.Path

/**
 * End-to-end tests for the BPMN guardrail pipeline, covering readiness assessment,
 * process contract extraction, and semantic alignment.
 *
 * NOTE: These tests verify BpmnGenerationUseCase, which is the orchestrator responsible for gating.
 */
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "bpmner.readiness.ready-threshold=75",
        "bpmner.readiness.clarification-threshold=40",
    ],
)
class BpmnGuardrailSystemTest(
    @Autowired private val generationUseCase: BpmnGenerationUseCase,
) : EmbabelMockitoIntegrationTest() {

    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintingPort: BpmnLintingPort

    @Test
    fun `blocks generation when input lacks workflow signal`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("no_workflow.bpmn")
        val input = BpmnGenerationInput(processDescription = "I want to buy some apples.", outputFile = outputFile.toString())

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                overallScore = 20,
                dimensions =
                listOf(
                    ReadinessDimensionScore(
                        ReadinessDimension.BPMN_SUITABILITY,
                        20,
                        "No sequenced workflow found in the source text.",
                    ),
                ),
                rationale = "The input is a simple statement, not a workflow.",
                missingAreas = listOf(MissingProcessArea.BPMN_PROCESS_SUITABILITY),
            ),
        )

        val result = generationUseCase.generate(input)

        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION, result.status)
        assertNull(result.xml)
        assertTrue(result.reportFile != null)
    }

    @Test
    fun `blocks generation for input that NEEDS_CLARIFICATION`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("needs_clarification.bpmn")
        // Include enough process markers to satisfy the deterministic post-checker for clarification.
        val input =
            BpmnGenerationInput(
                processDescription = "Starts when requested. Do something. Ends when complete.",
                outputFile = outputFile.toString(),
            )

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                overallScore = 60,
                dimensions =
                listOf(
                    ReadinessDimensionScore(ReadinessDimension.SEQUENCE_ORDER, 60, "Activities are vague."),
                ),
                rationale = "Input is missing activity sequence.",
                missingAreas = listOf(MissingProcessArea.ACTIVITY_SEQUENCE),
                clarificationQuestions =
                listOf(
                    ClarificationQuestion(
                        "q1",
                        "What are the specific activities?",
                        listOf(MissingProcessArea.ACTIVITY_SEQUENCE),
                        listOf(ReadinessDimension.SEQUENCE_ORDER),
                    ),
                ),
            ),
        )

        val result = generationUseCase.generate(input)

        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION, result.status)
        assertNull(result.xml)
        assertEquals(1, result.readinessReport?.clarificationQuestions?.size)
    }

    @Test
    fun `blocks generation when alignment fails`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("alignment_fail.bpmn")
        // Include exhaustive process markers to ensure a READY verdict from the deterministic post-checker.
        val processDescription =
            "The process starts when an order is received. First, we review the order, then we approve it, then we ship it. " +
                "Finally, the process ends when the order is complete."
        val input = BpmnGenerationInput(processDescription = processDescription, outputFile = outputFile.toString())

        // 1. Assessment passes
        whenCreateObject(
            { it.contains("Assess whether the source text describes a workflow") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())

        // 2. Contract extraction passes
        whenCreateObject(
            { it.contains("Extract a source-grounded process contract") },
            FlatProcessContract::class.java,
        ).thenReturn(validFlatContract())

        // 3. Generation produces a definition
        whenCreateObject(
            { it.contains("Generate a BPMN definition object") },
            BpmnDefinition::class.java,
        ).thenReturn(validDefinition())

        // 4. Validation passes (XSD + Lint)
        `when`(bpmnXsdValidator.validateDetailed(anyNonNull())).thenReturn(emptyList())
        doReturn(emptyList<LintIssue>()).`when`(bpmnLintingPort).lint(anyNonNull())

        // 5. Alignment check fails (LLM detects invented tasks)
        val alignmentPrompt = "You are a BPMN alignment validator"
        whenCreateObject(
            { it.contains(alignmentPrompt) },
            AlignmentFindings::class.java,
        ).thenReturn(
            AlignmentFindings(
                issues =
                listOf(
                    AlignmentIssue(
                        elementId = "Task_Invented",
                        classification = AlignmentClassification.UNSUPPORTED,
                    ),
                ),
                rationale = "BPMN contains invented tasks not in contract.",
            ),
        )

        val result = generationUseCase.generate(input)

        assertEquals(BpmnGenerationStatus.ALIGNMENT_FAILED, result.status)
        assertNull(result.xml)
    }

    private fun <T> anyNonNull(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return castNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castNull(): T = null as T

    private fun validAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions =
        listOf(
            ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "Explicit trigger."),
        ),
        evidence =
        listOf(
            SourceEvidence("ev1", "When an order is submitted", EvidenceSourceType.ORIGINAL_INPUT),
        ),
        rationale = "Input is ready.",
    )

    private fun validFlatContract() = FlatProcessContract(
        id = "c1",
        processName = "Order",
        summary = "Handle order.",
        start = FlatContractStart(
            trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Order submitted"),
            sourceIds = listOf("ev1"),
        ),
        activities = listOf(
            FlatContractActivity(
                id = "a1",
                name = "Review",
                kind = FlatActivityKind.SERVICE,
                sourceIds = listOf("ev1"),
            ),
            FlatContractActivity(
                id = "a2",
                name = "Ship",
                kind = FlatActivityKind.SERVICE,
                sourceIds = listOf("ev1"),
            ),
        ),
        endStates = listOf(
            FlatContractEndState(
                id = "e1",
                name = "Shipped",
                kind = FlatEndStateKind.NORMAL,
                sourceIds = listOf("ev1"),
            ),
        ),
    )

    private fun validDefinition() = BpmnDefinition(
        processId = "P1",
        processName = "Order",
        nodes =
        listOf(
            BpmnStartEvent("S1", "Start"),
            BpmnUserTask("T1", "Review"),
            BpmnEndEvent("E1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(
                "F1",
                "S1",
                "T1",
            ),
            BpmnEdge(
                "F2",
                "T1",
                "E1",
            ),
        ),
    )
}
