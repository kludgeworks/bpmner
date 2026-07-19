/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.outbound

import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@InfrastructureRing
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
    ): String = buildString {
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

    private fun assumptionPhrase(area: ReadinessDimension): String = when (area) {
        ReadinessDimension.PROCESS_BOUNDARY -> {
            "process boundary would be assumed"
        }

        ReadinessDimension.START_TRIGGER -> {
            "a start trigger would be invented"
        }

        ReadinessDimension.END_STATES -> {
            "an end state would be invented"
        }

        ReadinessDimension.ACTIVITIES -> {
            "activities would be invented"
        }

        ReadinessDimension.SEQUENCE_ORDER -> {
            "activity order would be invented"
        }

        ReadinessDimension.ACTORS_ROLES -> {
            "actor responsibilities would be assigned without grounding"
        }

        ReadinessDimension.DECISIONS_BRANCHES -> {
            "decision criteria would be invented"
        }

        ReadinessDimension.EXCEPTIONS_REWORK -> {
            "exception paths would be invented"
        }

        ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS -> {
            "input/output artifacts would be invented"
        }

        ReadinessDimension.SCOPE_CLARITY -> {
            "the process's scope boundaries would be assumed — where it starts/ends and what's in/out of scope"
        }

        ReadinessDimension.BPMN_SUITABILITY -> {
            "the source text would be treated as a process despite weak suitability"
        }

        ReadinessDimension.TRACEABILITY_TO_SOURCE -> {
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
