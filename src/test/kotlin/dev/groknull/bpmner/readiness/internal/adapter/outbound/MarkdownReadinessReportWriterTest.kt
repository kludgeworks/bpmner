/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.outbound

import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class MarkdownReadinessReportWriterTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes markdown report beside output path with all required sections`() {
        val writer = MarkdownReadinessReportWriter()
        val outputFile = tempDir.resolve("weak.bpmn").toString()
        val assessment =
            ProcessInputAssessment(
                verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                overallScore = 55,
                dimensions =
                    ReadinessDimension.entries.map { dim ->
                        ReadinessDimensionScore(
                            dimension = dim,
                            score = if (dim == ReadinessDimension.START_TRIGGER) 30 else 60,
                            rationale = "Dimension rationale for ${dim.name}.",
                            missingAreas =
                                if (dim == ReadinessDimension.START_TRIGGER) {
                                    listOf(MissingProcessArea.START_TRIGGER)
                                } else {
                                    emptyList()
                                },
                        )
                    },
                missingAreas = listOf(MissingProcessArea.START_TRIGGER, MissingProcessArea.ACTIVITY_SEQUENCE),
                clarificationQuestions =
                    listOf(
                        ClarificationQuestion(
                            id = "q1",
                            questionText = "What event starts the process?",
                            relatedMissingAreas = listOf(MissingProcessArea.START_TRIGGER),
                            relatedDimensions = listOf(ReadinessDimension.START_TRIGGER),
                        ),
                    ),
                rationale = "Source is too short to ground a workflow.",
            )

        val reportPath =
            writer.writeReport(
                originalInput = "  Make it better.  ",
                assessment = assessment,
                outputFile = outputFile,
            )

        val expectedPath = tempDir.resolve("weak.bpmn.readiness.md").toString()
        assertEquals(expectedPath, reportPath)
        assertTrue(Files.exists(Path.of(reportPath)))
        val content = Files.readString(Path.of(reportPath), StandardCharsets.UTF_8)
        assertTrue(content.contains("# BPMN readiness report"))
        assertTrue(content.contains("**Verdict:** NEEDS_CLARIFICATION"))
        assertTrue(content.contains("**Overall score:** 55"))
        assertTrue(content.contains("## Missing dimensions"))
        assertTrue(content.contains("START_TRIGGER (score 30)"))
        assertTrue(content.contains("## Assumptions that would be required to proceed"))
        assertTrue(content.contains("START_TRIGGER"))
        assertTrue(content.contains("ACTIVITY_SEQUENCE"))
        assertTrue(content.contains("## Clarification questions"))
        assertTrue(content.contains("[q1] What event starts the process?"))
        assertTrue(content.contains("## Rationale"))
        assertTrue(content.contains("Source is too short to ground a workflow."))
        assertTrue(content.contains("## Original input (preview)"))
        assertTrue(content.contains("Make it better."))
    }

    @Test
    fun `truncates long input preview`() {
        val writer = MarkdownReadinessReportWriter()
        val outputFile = tempDir.resolve("long.bpmn").toString()
        val longInput = "x".repeat(2_000)

        val reportPath =
            writer.writeReport(
                originalInput = longInput,
                assessment = minimalAssessment(),
                outputFile = outputFile,
            )

        val content = Files.readString(Path.of(reportPath), StandardCharsets.UTF_8)
        assertTrue(content.contains("…"), "Long inputs should be truncated with an ellipsis marker")
    }

    private fun minimalAssessment(): ProcessInputAssessment =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 10,
            dimensions =
                ReadinessDimension.entries.map {
                    ReadinessDimensionScore(
                        dimension = it,
                        score = 10,
                        rationale = "Stubbed rationale.",
                    )
                },
            rationale = "Stub.",
        )
}
