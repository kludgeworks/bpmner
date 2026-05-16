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

package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.ClarificationQuestion
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption

@PrimaryAdapter
@ShellComponent
class BpmnShellCommands(
    private val generationUseCase: BpmnGenerationUseCase,
    private val prompter: BpmnShellPrompter,
) {
    @ShellMethod(
        value = "Generate a BPMN 2.0 diagram from a process description",
        key = ["generate", "gen"],
    )
    fun generate(
        @ShellOption(
            value = ["--process"],
            help = "Natural-language process description",
            defaultValue = ShellOption.NULL,
        ) processDescription: String?,
        @ShellOption(
            value = ["--process-file"],
            help = "Path to a file containing the process description",
            defaultValue = ShellOption.NULL,
        ) processFile: String?,
        @ShellOption(
            value = ["--output"],
            help = "BPMN output path",
            defaultValue = "output.bpmn",
        ) output: String,
        @ShellOption(
            value = ["--style-guide"],
            help = "Optional Markdown style guide path",
            defaultValue = ShellOption.NULL,
        ) styleGuide: String?,
    ): String {
        val input =
            BpmnGenerationInput(
                processDescription = processDescription,
                processFile = processFile,
                outputFile = output,
                styleGuide = styleGuide,
                mode = GenerationMode.INTERACTIVE,
            )
        val result = generationUseCase.generate(input)
        if (result.status != BpmnGenerationStatus.NEEDS_CLARIFICATION) {
            return responseFor(result)
        }

        val questions = result.readinessReport?.clarificationQuestions.orEmpty()
        if (questions.isEmpty()) return responseFor(result)

        val clarifications = askClarificationQuestions(questions)
        if (clarifications.isEmpty()) {
            return "Clarification still required. ${readinessSummary(result)}"
        }

        val updatedResult =
            generationUseCase.generate(
                input.copy(clarificationHistory = clarifications),
            )
        return responseFor(updatedResult)
    }

    private fun askClarificationQuestions(questions: List<ClarificationQuestion>): List<ClarificationExchange> =
        questions.mapNotNull { question ->
            val answer = prompter.ask(question)?.trim().orEmpty()
            if (answer.isBlank()) return@mapNotNull null
            ClarificationExchange(
                questionId = question.id,
                questionText = question.questionText,
                answerText = answer,
                relatedMissingAreas = question.relatedMissingAreas,
                relatedDimensions = question.relatedDimensions,
                evidence =
                    listOf(
                        SourceEvidence(
                            id = "clarification-${question.id}",
                            text = answer,
                            sourceType = EvidenceSourceType.CLARIFICATION,
                            sourceRef = question.id,
                        ),
                    ),
            )
        }

    private fun responseFor(result: BpmnResult): String =
        when (result.status) {
            BpmnGenerationStatus.GENERATED -> {
                "BPMN written to: ${result.outputFile ?: "(none)"}"
            }

            BpmnGenerationStatus.NEEDS_CLARIFICATION -> {
                "Clarification required. ${readinessSummary(result)}"
            }

            BpmnGenerationStatus.NOT_A_PROCESS -> {
                "BPMN not generated. ${readinessSummary(result)}"
            }

            BpmnGenerationStatus.ALIGNMENT_FAILED -> {
                "BPMN not generated because semantic alignment failed.${reportFileSuffix(result)}"
            }

            BpmnGenerationStatus.VALIDATION_FAILED -> {
                "BPMN not generated because validation failed.${reportFileSuffix(result)}"
            }
        }

    private fun readinessSummary(result: BpmnResult): String {
        val report = result.readinessReport ?: return reportFileSuffix(result)
        val missing = missingAreasSummary(report.missingAreas)
        val reportFile = reportFileSuffix(result)
        return "Verdict=${report.verdict}, score=${report.overallScore}$missing$reportFile"
    }

    private fun missingAreasSummary(missingAreas: List<MissingProcessArea>): String =
        if (missingAreas.isEmpty()) {
            ""
        } else {
            ", missing=${missingAreas.joinToString { it.name }}"
        }

    private fun reportFileSuffix(result: BpmnResult): String = result.reportFile?.let { ", report=$it" }.orEmpty()
}
