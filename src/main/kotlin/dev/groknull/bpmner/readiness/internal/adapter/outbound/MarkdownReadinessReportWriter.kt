package dev.groknull.bpmner.readiness.internal.adapter.outbound

import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@SecondaryAdapter
@Component
internal class MarkdownReadinessReportWriter : ReadinessReportWriter {
    override fun writeReport(
        originalInput: String,
        assessment: ProcessInputAssessment,
        outputFile: String?,
    ): String {
        val outPath = Path.of(outputFile ?: DEFAULT_OUTPUT_FILE)
        val reportPath = outPath.resolveSibling(outPath.fileName.toString() + READINESS_REPORT_SUFFIX)
        reportPath.parent?.let { Files.createDirectories(it) }
        Files.writeString(reportPath, render(originalInput, assessment), StandardCharsets.UTF_8)
        return reportPath.toString()
    }

    private fun render(
        originalInput: String,
        assessment: ProcessInputAssessment,
    ): String =
        buildString {
            appendLine("# BPMN readiness report")
            appendLine()
            appendLine("**Verdict:** ${assessment.verdict.name}")
            appendLine("**Overall score:** ${assessment.overallScore}")
            appendLine()
            appendLine("## Missing dimensions")
            val missingDims = assessment.dimensions.filter { it.missingAreas.isNotEmpty() }
            if (missingDims.isEmpty()) {
                appendLine("- (none flagged by readiness assessor)")
            } else {
                missingDims.forEach { dim ->
                    appendLine("- ${dim.dimension.name} (score ${dim.score}) — ${dim.rationale}")
                }
            }
            appendLine()
            appendLine("## Assumptions that would be required to proceed")
            val assumptions = assessment.missingAreas.distinct()
            if (assumptions.isEmpty()) {
                appendLine("- (no missing process areas reported)")
            } else {
                assumptions.forEach { area ->
                    appendLine("- ${area.name} — ${assumptionPhrase(area)}")
                }
            }
            appendLine()
            appendLine("## Clarification questions")
            if (assessment.clarificationQuestions.isEmpty()) {
                appendLine("- (no clarification questions available)")
            } else {
                assessment.clarificationQuestions.forEachIndexed { index, question ->
                    appendLine("${index + 1}. [${question.id}] ${question.questionText}")
                }
            }
            appendLine()
            appendLine("## Rationale")
            appendLine(assessment.rationale)
            appendLine()
            appendLine("## Original input (preview)")
            appendLine()
            appendLine("```")
            appendLine(previewOf(originalInput))
            appendLine("```")
        }

    private fun previewOf(input: String): String {
        val collapsed = input.trim()
        return if (collapsed.length <= INPUT_PREVIEW_LENGTH) {
            collapsed
        } else {
            collapsed.take(INPUT_PREVIEW_LENGTH).trimEnd() + INPUT_PREVIEW_TRUNCATION_MARKER
        }
    }

    private fun assumptionPhrase(area: MissingProcessArea): String =
        when (area) {
            MissingProcessArea.PROCESS_BOUNDARY -> {
                "process boundary would be assumed"
            }

            MissingProcessArea.START_TRIGGER -> {
                "a start trigger would be invented"
            }

            MissingProcessArea.END_STATE -> {
                "an end state would be invented"
            }

            MissingProcessArea.ACTIVITY_SEQUENCE -> {
                "activities and their order would be invented"
            }

            MissingProcessArea.ACTOR_RESPONSIBILITY -> {
                "actor responsibilities would be assigned without grounding"
            }

            MissingProcessArea.DECISION_CRITERIA -> {
                "decision criteria would be invented"
            }

            MissingProcessArea.EXCEPTION_HANDLING -> {
                "exception paths would be invented"
            }

            MissingProcessArea.INPUT_ARTIFACT -> {
                "input artifacts would be invented"
            }

            MissingProcessArea.OUTPUT_ARTIFACT -> {
                "output artifacts would be invented"
            }

            MissingProcessArea.BPMN_PROCESS_SUITABILITY -> {
                "the source text would be treated as a process despite weak suitability"
            }

            MissingProcessArea.SOURCE_TRACE -> {
                "process details would be added without traceability to the source"
            }
        }

    private companion object {
        const val DEFAULT_OUTPUT_FILE = "output.bpmn"
        const val READINESS_REPORT_SUFFIX = ".readiness.md"
        const val INPUT_PREVIEW_LENGTH = 1000
        const val INPUT_PREVIEW_TRUNCATION_MARKER = "\n…"
    }
}
