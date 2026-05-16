/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignedElement
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
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
class BpmnGuardrailSystemTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var generationUseCase: BpmnGenerationUseCase

    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @MockitoBean
    private lateinit var bpmnLintService: BpmnLintService

    @Test
    fun `blocks generation for input that is NOT_A_PROCESS`(
        @TempDir tempDir: Path,
    ) {
        val outputFile = tempDir.resolve("not_a_process.bpmn")
        val input = BpmnGenerationInput(processDescription = "I want to buy some apples.", outputFile = outputFile.toString())

        whenCreateObject(
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(
            ProcessInputAssessment(
                verdict = ReadinessVerdict.NOT_A_PROCESS,
                overallScore = 20,
                dimensions =
                    listOf(
                        ReadinessDimensionScore(
                            ReadinessDimension.BPMN_SUITABILITY,
                            20,
                            "Not a repeatable business process.",
                        ),
                    ),
                rationale = "The input is a simple statement, not a process.",
                missingAreas = listOf(MissingProcessArea.BPMN_PROCESS_SUITABILITY),
            ),
        )

        val result = generationUseCase.generate(input)

        assertEquals(BpmnGenerationStatus.NOT_A_PROCESS, result.status)
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
            { it.contains("Return only a structured ProcessInputAssessment object.") },
            ProcessInputAssessment::class.java,
        ).thenReturn(validAssessment())

        // 2. Contract extraction passes
        whenCreateObject(
            { it.contains("Return only a structured ProcessContract object.") },
            ProcessContract::class.java,
        ).thenReturn(validContract())

        // 3. Generation produces a definition
        whenCreateObject(
            { it.contains("Generate a BPMN definition object") },
            BpmnDefinition::class.java,
        ).thenReturn(validDefinition())

        // 4. Validation passes (XSD + Lint)
        `when`(bpmnXsdValidator.validateDetailed(anyNonNull())).thenReturn(emptyList())
        doReturn(emptyList<LintIssue>()).`when`(bpmnLintService).lint(anyNonNull(), anyNonNull())

        // 5. Alignment check fails (LLM detects invented tasks)
        val alignmentPrompt = "Assess whether generated BPMN aligns semantically with process contract"
        whenCreateObject(
            { it.contains(alignmentPrompt) },
            BpmnAlignmentReport::class.java,
        ).thenReturn(
            BpmnAlignmentReport(
                verdict = AlignmentVerdict.FAILED,
                rationale = "BPMN contains invented tasks not in contract.",
                alignedElements =
                    listOf(
                        AlignedElement(
                            id = "ae1",
                            bpmnElementId = "Task_Invented",
                            classification = AlignmentClassification.UNSUPPORTED,
                            rationale = "Task not in contract",
                        ),
                    ),
                bpmnSummary = BpmnDefinitionSummary("P1", "Invented", emptyList()),
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

    private fun validAssessment() =
        ProcessInputAssessment(
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

    private fun validContract() =
        ProcessContract(
            id = "c1",
            processName = "Order",
            summary = "Handle order.",
            trigger = "Order submitted",
            triggerTraceLinks = listOf(TraceLink("t1", "ev1", "trigger")),
            activities =
                listOf(
                    ContractActivity("a1", "Review", traceLinks = listOf(TraceLink("t1", "ev1", "a1"))),
                    ContractActivity("a2", "Ship", traceLinks = listOf(TraceLink("t1", "ev1", "a2"))),
                ),
            endStates =
                listOf(
                    ContractEndState("e1", "Shipped", traceLinks = listOf(TraceLink("t1", "ev1", "e1"))),
                ),
        )

    private fun validDefinition() =
        BpmnDefinition(
            processId = "P1",
            processName = "Order",
            nodes =
                listOf(
                    BpmnNode("S1", "Start", NodeType.START_EVENT, BpmnBounds(0.0, 0.0, 36.0, 36.0)),
                    BpmnNode("T1", "Review", NodeType.USER_TASK, BpmnBounds(100.0, 0.0, 100.0, 80.0)),
                    BpmnNode("E1", "End", NodeType.END_EVENT, BpmnBounds(300.0, 0.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "F1",
                        "S1",
                        "T1",
                        waypoints = listOf(BpmnWaypoint(36.0, 18.0), BpmnWaypoint(100.0, 18.0)),
                    ),
                    BpmnEdge(
                        "F2",
                        "T1",
                        "E1",
                        waypoints = listOf(BpmnWaypoint(200.0, 18.0), BpmnWaypoint(300.0, 18.0)),
                    ),
                ),
        )
}
