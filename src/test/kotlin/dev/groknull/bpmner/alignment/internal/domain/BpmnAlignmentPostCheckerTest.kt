/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.AlignmentIssue
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnAlignmentConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BpmnAlignmentPostCheckerTest {
    private val summary =
        BpmnDefinitionSummary(
            processId = "P1",
            processName = "Test",
            elements = listOf(BpmnSummaryElement(id = "Task_A", type = "TASK", name = "Do thing")),
        )

    @Test
    fun `empty issues yields ALIGNED verdict`() {
        val postChecker = BpmnAlignmentPostChecker(BpmnAlignmentConfig())
        val findings = AlignmentFindings(issues = emptyList(), rationale = "All good.")

        val report = postChecker.apply(findings, summary)

        assertEquals(AlignmentVerdict.ALIGNED, report.verdict)
        assertEquals(summary, report.bpmnSummary)
        assertEquals(findings.issues, report.issues)
        assertEquals("All good.", report.rationale)
    }

    @Test
    fun `single UNSUPPORTED issue with blockOnUnsupportedElements yields FAILED`() {
        val postChecker = BpmnAlignmentPostChecker(BpmnAlignmentConfig(blockOnUnsupportedElements = true))
        val findings =
            AlignmentFindings(
                issues = listOf(AlignmentIssue("Task_Invented", AlignmentClassification.UNSUPPORTED)),
                rationale = "Invented task not in contract.",
            )

        assertEquals(AlignmentVerdict.FAILED, postChecker.apply(findings, summary).verdict)
    }

    @Test
    fun `single MISSING issue with blockOnMissingContractItems yields FAILED`() {
        val postChecker = BpmnAlignmentPostChecker(BpmnAlignmentConfig(blockOnMissingContractItems = true))
        val findings =
            AlignmentFindings(
                issues = listOf(AlignmentIssue("activity-validate", AlignmentClassification.MISSING)),
                rationale = "Validation step missing.",
            )

        assertEquals(AlignmentVerdict.FAILED, postChecker.apply(findings, summary).verdict)
    }

    @Test
    fun `assumptions over threshold yield FAILED`() {
        val postChecker = BpmnAlignmentPostChecker(BpmnAlignmentConfig(maxAssumptions = 1))
        val findings =
            AlignmentFindings(
                issues =
                listOf(
                    AlignmentIssue("Task_A", AlignmentClassification.ASSUMED),
                    AlignmentIssue("Task_B", AlignmentClassification.ASSUMED),
                ),
                rationale = "Two assumed tasks.",
            )

        assertEquals(AlignmentVerdict.FAILED, postChecker.apply(findings, summary).verdict)
    }

    @Test
    fun `non-blocking issues yield PARTIALLY_ALIGNED`() {
        val postChecker =
            BpmnAlignmentPostChecker(
                BpmnAlignmentConfig(
                    blockOnUnsupportedElements = false,
                    blockOnMissingContractItems = false,
                    maxAssumptions = 10,
                ),
            )
        val findings =
            AlignmentFindings(
                issues =
                listOf(
                    AlignmentIssue("Task_A", AlignmentClassification.PARTIALLY_COVERED),
                ),
                rationale = "Partial coverage.",
            )

        assertEquals(AlignmentVerdict.PARTIALLY_ALIGNED, postChecker.apply(findings, summary).verdict)
    }

    @Test
    fun `summary is always replaced with framework-computed value`() {
        val postChecker = BpmnAlignmentPostChecker(BpmnAlignmentConfig())
        val findings = AlignmentFindings(issues = emptyList(), rationale = "ok")

        val report = postChecker.apply(findings, summary)

        // Even when the LLM said nothing about summary, the framework-supplied one is in the report.
        assertEquals(summary, report.bpmnSummary)
    }
}
