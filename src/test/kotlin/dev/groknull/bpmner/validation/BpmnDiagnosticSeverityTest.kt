/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnDiagnosticSeverityTest {
    @Test
    fun `fromLintCategory maps error case-insensitively`() {
        assertEquals(BpmnDiagnosticSeverity.ERROR, BpmnDiagnosticSeverity.fromLintCategory("error"))
        assertEquals(BpmnDiagnosticSeverity.ERROR, BpmnDiagnosticSeverity.fromLintCategory("ERROR"))
        assertEquals(BpmnDiagnosticSeverity.ERROR, BpmnDiagnosticSeverity.fromLintCategory("Error"))
    }

    @Test
    fun `fromLintCategory accepts both warn and warning`() {
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory("warn"))
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory("warning"))
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory("WARN"))
    }

    @Test
    fun `fromLintCategory maps info`() {
        assertEquals(BpmnDiagnosticSeverity.INFO, BpmnDiagnosticSeverity.fromLintCategory("info"))
    }

    @Test
    fun `fromLintCategory defaults to WARNING for null or unrecognised values`() {
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory(null))
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory(""))
        assertEquals(BpmnDiagnosticSeverity.WARNING, BpmnDiagnosticSeverity.fromLintCategory("critical"))
    }

    @Test
    fun `BpmnDiagnostic isBlocking reflects severity`() {
        assertTrue(diag(BpmnDiagnosticSeverity.ERROR).isBlocking)
        assertFalse(diag(BpmnDiagnosticSeverity.WARNING).isBlocking)
        assertFalse(diag(BpmnDiagnosticSeverity.INFO).isBlocking)
    }

    @Test
    fun `BpmnDiagnostic defaults to WARNING severity`() {
        val d = BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "x")
        assertEquals(BpmnDiagnosticSeverity.WARNING, d.severity)
    }

    @Test
    fun `format includes severity in its output`() {
        val d =
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = "Event name should describe a state",
                severity = BpmnDiagnosticSeverity.WARNING,
                rule = "bpmner/evt-event-state-name",
                elementId = "end-1",
            )
        val s = d.format()
        assertTrue(s.contains("severity=warning"), "format must surface severity; got: $s")
        assertTrue(s.contains("rule=bpmner/evt-event-state-name"))
    }

    private fun diag(severity: BpmnDiagnosticSeverity): BpmnDiagnostic {
        return BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "x", severity = severity)
    }
}
