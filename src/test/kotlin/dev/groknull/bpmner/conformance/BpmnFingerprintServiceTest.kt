/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BpmnFingerprintServiceTest {
    private val service = BpmnFingerprintService()
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `definition serialization is parseable and fingerprints semantic changes`() {
        val definition = representativeDefinition()
        val serialized = service.serializeDefinition(definition)
        val json = objectMapper.readTree(serialized)

        assertTrue(serialized.isNotBlank())
        assertEquals(
            setOf(
                "processId",
                "processName",
                "nodes",
                "sequences",
                "messages",
                "errors",
                "annotations",
                "groups",
                "associations",
                "participants",
                "lanes",
                "messageFlows",
            ),
            json.fieldNames().asSequence().toSet(),
        )
        assertEquals(definition.processId, json["processId"].asText())
        assertEquals(definition.nodes.size, json["nodes"].size())
        assertEquals(definition.sequences.size, json["sequences"].size())

        assertEquals(service.definitionFingerprint(definition), service.definitionFingerprint(definition))
        assertNotEquals(
            service.definitionFingerprint(definition),
            service.definitionFingerprint(definition.copy(processName = "Approve reimbursement claim")),
        )
    }

    @Test
    fun `diagnosticFingerprint differs when any field changes`() {
        val a = lint(severity = BpmnDiagnosticSeverity.ERROR, message = "x")
        val b = lint(severity = BpmnDiagnosticSeverity.ERROR, message = "y")
        assertNotEquals(service.diagnosticFingerprint(listOf(a)), service.diagnosticFingerprint(listOf(b)))
    }

    @Test
    fun `diagnosticFingerprint is order-insensitive`() {
        val a = lint(rule = "bpmner/x", message = "a")
        val b = lint(rule = "bpmner/y", message = "b")
        assertEquals(
            service.diagnosticFingerprint(listOf(a, b)),
            service.diagnosticFingerprint(listOf(b, a)),
        )
    }

    @Test
    fun `blockingDiagnosticFingerprint covers ONLY blocking diagnostics`() {
        // The two sets differ only in advisory warnings. The blocking-only fingerprint must
        // be stable across the difference; the full fingerprint must NOT be.
        val blocking = lint(severity = BpmnDiagnosticSeverity.ERROR, message = "stuck")
        val advisoryA = lint(severity = BpmnDiagnosticSeverity.WARNING, message = "advisory A")
        val advisoryB = lint(severity = BpmnDiagnosticSeverity.WARNING, message = "advisory B")

        val setA = listOf(blocking, advisoryA)
        val setB = listOf(blocking, advisoryB)

        // Full fingerprint changes because advisories differ.
        assertNotEquals(service.diagnosticFingerprint(setA), service.diagnosticFingerprint(setB))
        // Blocking-only fingerprint stays the same — the stuck error is unchanged.
        assertEquals(
            service.blockingDiagnosticFingerprint(setA),
            service.blockingDiagnosticFingerprint(setB),
        )
    }

    @Test
    fun `blockingDiagnosticFingerprint is empty-stable when no blocking diagnostics`() {
        val warningsOnly = listOf(lint(severity = BpmnDiagnosticSeverity.WARNING, message = "x"))
        val moreWarnings =
            listOf(
                lint(severity = BpmnDiagnosticSeverity.WARNING, message = "x"),
                lint(severity = BpmnDiagnosticSeverity.INFO, message = "y"),
            )
        // No blocking diagnostics in either set — fingerprint over the (empty) blocking subset
        // is identical and equal to the fingerprint over the empty input.
        assertEquals(
            service.blockingDiagnosticFingerprint(emptyList()),
            service.blockingDiagnosticFingerprint(warningsOnly),
        )
        assertEquals(
            service.blockingDiagnosticFingerprint(warningsOnly),
            service.blockingDiagnosticFingerprint(moreWarnings),
        )
    }

    @Test
    fun `blockingDiagnosticFingerprint flips when a blocking diagnostic actually changes`() {
        val before = listOf(lint(severity = BpmnDiagnosticSeverity.ERROR, message = "first"))
        val after = listOf(lint(severity = BpmnDiagnosticSeverity.ERROR, message = "second"))
        assertNotEquals(
            service.blockingDiagnosticFingerprint(before),
            service.blockingDiagnosticFingerprint(after),
        )
    }

    @Test
    fun `fingerprints are deterministic - same input - same output`() {
        val input = listOf(lint(rule = "a"), lint(rule = "b"))
        repeat(5) {
            assertEquals(
                service.diagnosticFingerprint(input),
                service.diagnosticFingerprint(input),
            )
            assertEquals(
                service.blockingDiagnosticFingerprint(input),
                service.blockingDiagnosticFingerprint(input),
            )
        }
        // Length contract from FINGERPRINT_LENGTH.
        assertTrue(service.diagnosticFingerprint(input).length == 12)
    }

    private fun lint(
        severity: BpmnDiagnosticSeverity = BpmnDiagnosticSeverity.WARNING,
        rule: String = "bpmner/test-rule",
        message: String = "diagnostic",
    ) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = message,
        severity = severity,
        rule = rule,
        elementId = "node-1",
    )

    private fun representativeDefinition() = BpmnDefinition(
        processId = "Process_reimbursement",
        processName = "Review reimbursement claim",
        nodes =
        listOf(
            BpmnStartEvent(id = "Start_claim", name = "Claim received"),
            BpmnUserTask(id = "Task_review", name = "Review claim"),
            BpmnEndEvent(id = "End_claim", name = "Claim completed"),
        ),
        sequences =
        listOf(
            BpmnEdge(id = "Flow_start_review", sourceRef = "Start_claim", targetRef = "Task_review"),
            BpmnEdge(id = "Flow_review_end", sourceRef = "Task_review", targetRef = "End_claim"),
        ),
    )
}
