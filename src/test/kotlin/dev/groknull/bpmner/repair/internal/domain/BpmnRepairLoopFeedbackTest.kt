/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.repair.BpmnRepairBudgetConfig
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for [BpmnRepairLoop.feedbackFor]: the corrective-feedback text sent back to
 * the model on a failed repair attempt. A diagnostic with neither a lint [BpmnDiagnostic.rule] nor
 * an [BpmnDiagnostic.elementId] — e.g. a graph-connectivity error from
 * `BpmnDefinitionValidator.validateFlowNodeConnectivity` — must still surface its
 * [BpmnDiagnostic.message], not a placeholder the model cannot act on.
 */
class BpmnRepairLoopFeedbackTest {
    private val loop = BpmnRepairLoop(
        deterministicNormalizer = mock(BpmnDeterministicNormalizer::class.java),
        llmRepairApplier = mock(BpmnLlmRepairApplier::class.java),
        config = BpmnRepairBudgetConfig(),
    )

    private fun diagnostic(
        message: String,
        rule: String? = null,
        elementId: String? = null,
    ): BpmnDiagnostic = BpmnDiagnostic(
        source = BpmnDiagnosticSource.GRAPH,
        message = message,
        severity = BpmnDiagnosticSeverity.ERROR,
        rule = rule,
        elementId = elementId,
    )

    @Test
    fun `no blocking diagnostics yields the fixed no-op message`() {
        assertEquals("No blocking diagnostics", loop.feedbackFor(emptyList()))
    }

    @Test
    fun `a diagnostic with a rule and elementId renders bracketed`() {
        val d = diagnostic(message = "irrelevant", rule = "gtw-fake-join", elementId = "act-x")
        assertEquals("[act-x] gtw-fake-join", loop.feedbackFor(listOf(d)))
    }

    @Test
    fun `a diagnostic with only an elementId renders the id`() {
        val d = diagnostic(message = "irrelevant", elementId = "act-x")
        assertEquals("act-x", loop.feedbackFor(listOf(d)))
    }

    @Test
    fun `a diagnostic with neither a rule nor an elementId falls back to its message`() {
        val d = diagnostic(message = "node act-abandon-order missing incoming sequence flow")
        assertEquals(
            "node act-abandon-order missing incoming sequence flow",
            loop.feedbackFor(listOf(d)),
        )
    }

    @Test
    fun `multiple diagnostics join with a semicolon`() {
        val d1 = diagnostic(message = "m1", elementId = "act-a")
        val d2 = diagnostic(message = "node act-b missing outgoing sequence flow")
        assertEquals("act-a; node act-b missing outgoing sequence flow", loop.feedbackFor(listOf(d1, d2)))
    }
}
