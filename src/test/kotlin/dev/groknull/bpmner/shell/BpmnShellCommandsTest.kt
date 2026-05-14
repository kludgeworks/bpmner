package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.BpmnGenerationStatus
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.core.ClarificationQuestion
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.ReadinessDimensionScore
import dev.groknull.bpmner.core.ReadinessVerdict
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
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

    private fun weakAssessment() =
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
                        id = "q-trigger",
                        questionText = "What starts the process?",
                        relatedMissingAreas = listOf(MissingProcessArea.START_TRIGGER),
                        relatedDimensions = listOf(ReadinessDimension.START_TRIGGER),
                    ),
                ),
            rationale = "The start trigger is missing.",
        )
}
