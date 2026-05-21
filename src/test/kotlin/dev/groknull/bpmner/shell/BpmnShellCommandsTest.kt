/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.StartGenerationOutcome
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnShellCommandsTest {
    @Test
    fun `generate forwards inline process description`() {
        val generationUseCase = CapturingGenerationUseCase()
        val commands = BpmnShellCommands(generationUseCase, CapturingPrompter())

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = "style.md",
            )

        assertEquals(
            BpmnGenerationInput(
                processDescription = "Ship order",
                outputFile = "ship.bpmn",
                styleGuide = "style.md",
                mode = GenerationMode.INTERACTIVE,
            ),
            generationUseCase.calls.single(),
        )
        assertEquals("BPMN written to: ship.bpmn", response)
    }

    @Test
    fun `generate forwards process file`() {
        val generationUseCase = CapturingGenerationUseCase()
        val commands = BpmnShellCommands(generationUseCase, CapturingPrompter())

        commands.generate(
            processDescription = null,
            processFile = "process.txt",
            output = "process.bpmn",
            styleGuide = null,
        )

        assertEquals(
            BpmnGenerationInput(
                processFile = "process.txt",
                outputFile = "process.bpmn",
                mode = GenerationMode.INTERACTIVE,
            ),
            generationUseCase.calls.single(),
        )
    }

    @Test
    fun `generate asks clarification questions and retries with structured answers`() {
        val firstResult =
            BpmnResult(
                outputFile = "ship.bpmn",
                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                readinessReport = weakAssessment(),
                reportFile = "ship.guardrails.md",
            )
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            firstResult,
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.GENERATED,
                                xml = "<definitions />",
                            ),
                        ),
                    ),
            )
        val prompter = CapturingPrompter(mapOf("q-trigger" to "The customer submits an order."))
        val commands = BpmnShellCommands(generationUseCase, prompter)

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertEquals("BPMN written to: ship.bpmn", response)
        assertEquals(1, prompter.questions.size)
        assertEquals(2, generationUseCase.calls.size)
        val retry = generationUseCase.calls[1]
        assertEquals(GenerationMode.INTERACTIVE, retry.mode)
        assertEquals("Ship order", retry.processDescription)
        val clarification = retry.clarificationHistory.single()
        assertEquals("q-trigger", clarification.questionId)
        assertEquals("The customer submits an order.", clarification.answerText)
        assertEquals(listOf(MissingProcessArea.START_TRIGGER), clarification.relatedMissingAreas)
        assertEquals(EvidenceSourceType.CLARIFICATION, clarification.evidence.single().sourceType)
        assertEquals("q-trigger", clarification.evidence.single().sourceRef)
    }

    @Test
    fun `alignment failed response includes report file path`() {
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.ALIGNMENT_FAILED,
                                reportFile = "ship.alignment.md",
                            ),
                        ),
                    ),
            )
        val commands = BpmnShellCommands(generationUseCase, CapturingPrompter())

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertTrue(response.contains("semantic alignment failed"))
        assertTrue(response.contains("report=ship.alignment.md"))
    }

    @Test
    fun `validation failed response includes report file path`() {
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.VALIDATION_FAILED,
                                reportFile = "ship.validation.md",
                            ),
                        ),
                    ),
            )
        val commands = BpmnShellCommands(generationUseCase, CapturingPrompter())

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertTrue(response.contains("validation failed"))
        assertTrue(response.contains("report=ship.validation.md"))
    }

    @Test
    fun `generate loops clarification across multiple rounds until ready`() {
        // Three sequential results: NEEDS_CLARIFICATION (round 1) → NEEDS_CLARIFICATION
        // (round 2, with a different question id) → GENERATED. The loop must call the
        // use case three times and accumulate clarifications across rounds.
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                                readinessReport = weakAssessment(questionId = "q-round-1"),
                            ),
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                                readinessReport = weakAssessment(questionId = "q-round-2"),
                            ),
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.GENERATED,
                                xml = "<definitions />",
                            ),
                        ),
                    ),
            )
        val prompter =
            CapturingPrompter(
                mapOf("q-round-1" to "Answer one", "q-round-2" to "Answer two"),
            )
        val commands = BpmnShellCommands(generationUseCase, prompter)

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertEquals("BPMN written to: ship.bpmn", response)
        assertEquals(3, generationUseCase.calls.size)
        // Final call carries both clarifications.
        assertEquals(
            listOf("q-round-1", "q-round-2"),
            generationUseCase.calls[2].clarificationHistory.map { it.questionId },
        )
    }

    @Test
    fun `generate stops after max clarification rounds`() {
        // Every round returns NEEDS_CLARIFICATION. The shell should give up after
        // MAX_CLARIFICATION_ROUNDS (3) iterations and return a distinct "limit reached"
        // message.
        val results =
            ArrayDeque<BpmnResult>().apply {
                repeat(4) {
                    add(
                        BpmnResult(
                            outputFile = "ship.bpmn",
                            status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                            readinessReport = weakAssessment(questionId = "q-r$it"),
                            reportFile = "ship.guardrails.md",
                        ),
                    )
                }
            }
        val prompter =
            CapturingPrompter(
                mapOf("q-r0" to "a", "q-r1" to "b", "q-r2" to "c", "q-r3" to "d"),
            )
        val generationUseCase = CapturingGenerationUseCase(results)
        val commands = BpmnShellCommands(generationUseCase, prompter)

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertEquals(3, generationUseCase.calls.size)
        assertTrue(response.contains("Clarification limit reached after 3 rounds"))
    }

    @Test
    fun `repeated question ids are not asked twice across rounds`() {
        // Both rounds surface the SAME question id (q-trigger). The dedup should
        // prevent the prompter from being asked twice for the same id. The second
        // round filters the question out, finds no new questions, and exits with
        // "Clarification still required" rather than looping further.
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                                readinessReport = weakAssessment(questionId = "q-trigger"),
                            ),
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                                readinessReport = weakAssessment(questionId = "q-trigger"),
                            ),
                        ),
                    ),
            )
        val prompter = CapturingPrompter(mapOf("q-trigger" to "An order arrives."))
        val commands = BpmnShellCommands(generationUseCase, prompter)

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertEquals(1, prompter.questions.size)
        assertTrue(response.contains("Clarification still required"))
    }

    @Test
    fun `blank clarification answers do not retry generation`() {
        val generationUseCase =
            CapturingGenerationUseCase(
                results =
                    ArrayDeque(
                        listOf(
                            BpmnResult(
                                outputFile = "ship.bpmn",
                                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                                readinessReport = weakAssessment(),
                                reportFile = "ship.guardrails.md",
                            ),
                        ),
                    ),
            )
        val commands = BpmnShellCommands(generationUseCase, CapturingPrompter(mapOf("q-trigger" to "   ")))

        val response =
            commands.generate(
                processDescription = "Ship order",
                processFile = null,
                output = "ship.bpmn",
                styleGuide = null,
            )

        assertEquals(1, generationUseCase.calls.size)
        assertTrue(response.contains("Clarification still required"))
        assertTrue(response.contains("report=ship.guardrails.md"))
    }

    private class CapturingGenerationUseCase : BpmnGenerationUseCase {
        constructor()

        constructor(results: ArrayDeque<BpmnResult>) {
            this.results = results
        }

        val calls = mutableListOf<BpmnGenerationInput>()
        private var results = ArrayDeque<BpmnResult>()

        override fun generate(input: BpmnGenerationInput): BpmnResult {
            calls += input
            if (results.isNotEmpty()) return results.removeFirst()
            return BpmnResult(
                outputFile = input.outputFile,
                status = BpmnGenerationStatus.GENERATED,
                xml = "<definitions />",
            )
        }

        override fun startAsync(input: BpmnGenerationInput): StartGenerationOutcome {
            val result = generate(input)
            return if (result.status == BpmnGenerationStatus.GENERATED) {
                StartGenerationOutcome.Started("p-42")
            } else {
                StartGenerationOutcome.Blocked(result)
            }
        }
    }

    private class CapturingPrompter(
        private val answers: Map<String, String> = emptyMap(),
    ) : BpmnShellPrompter {
        val questions = mutableListOf<ClarificationQuestion>()

        override fun ask(question: ClarificationQuestion): String? {
            questions += question
            return answers[question.id]
        }
    }

    private fun weakAssessment(questionId: String = "q-trigger") =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 60,
            dimensions =
                listOf(
                    ReadinessDimensionScore(
                        dimension = ReadinessDimension.START_TRIGGER,
                        score = 0,
                        rationale = "Start trigger missing.",
                        missingAreas = listOf(MissingProcessArea.START_TRIGGER),
                    ),
                ),
            missingAreas = listOf(MissingProcessArea.START_TRIGGER),
            clarificationQuestions =
                listOf(
                    ClarificationQuestion(
                        id = questionId,
                        questionText = "What starts the process?",
                        relatedMissingAreas = listOf(MissingProcessArea.START_TRIGGER),
                        relatedDimensions = listOf(ReadinessDimension.START_TRIGGER),
                    ),
                ),
            rationale = "The start trigger is missing.",
        )
}
