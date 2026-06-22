/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnerValidationEventCollectorTest {
    @Test
    fun `collector groups validation events by process id and removes them after read`() {
        val collector = BpmnerValidationEventCollector()
        val request = BpmnRequest("Approve order")

        collector.onValidationFailed(
            BpmnValidationFailedEvent(
                request = request,
                xml = "<definitions />",
                diagnostics =
                listOf(
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.LINT,
                        message = "Task name must use verb object",
                        rule = "bpmner/act-verb-object-name",
                    ),
                ),
                attemptNumber = 1,
                repairAttempts = 0,
                processId = "run-1",
            ),
        )
        collector.onValidationPassed(
            BpmnValidationPassedEvent(
                request = request,
                xml = "<definitions />",
                repairAttempts = 1,
                processId = "run-1",
            ),
        )

        val collected = collector.removeFor("run-1")

        assertEquals(1, collected.failed.size)
        assertEquals(1, collected.passed?.repairAttempts)
        assertTrue(collector.removeFor("run-1").failed.isEmpty())
        assertTrue(collector.removeFor("unknown").failed.isEmpty())
    }

    @Test
    fun `collector isolates identical requests by process id`() {
        val collector = BpmnerValidationEventCollector()
        val request = BpmnRequest("Approve order")

        collector.onValidationFailed(failedEvent(request, processId = "run-1", message = "run one failed"))
        collector.onValidationFailed(failedEvent(request.copy(), processId = "run-2", message = "run two failed"))

        assertEquals(listOf("run one failed"), collector.removeFor("run-1").failed.map { it.diagnostics.single().message })
        assertEquals(listOf("run two failed"), collector.removeFor("run-2").failed.map { it.diagnostics.single().message })
    }

    @Test
    fun `collector ignores request mutation because process id is stable`() {
        val collector = BpmnerValidationEventCollector()
        val original = BpmnRequest("Approve order")
        val evolved = original.copy(outputFile = "changed.bpmn")

        collector.onValidationFailed(failedEvent(original, processId = "run-1", message = "before change"))
        collector.onValidationPassed(
            BpmnValidationPassedEvent(
                request = evolved,
                xml = "<definitions />",
                repairAttempts = 1,
                processId = "run-1",
            ),
        )

        val collected = collector.removeFor("run-1")

        assertEquals(listOf("before change"), collected.failed.map { it.diagnostics.single().message })
        assertEquals(1, collected.passed?.repairAttempts)
    }

    @Test
    fun `collector ignores events without a process id`() {
        val collector = BpmnerValidationEventCollector()
        val request = BpmnRequest("Approve order")

        collector.onValidationFailed(failedEvent(request, processId = null, message = "ignored"))

        assertTrue(collector.removeFor("run-1").failed.isEmpty())
    }

    private fun failedEvent(
        request: BpmnRequest,
        processId: String?,
        message: String,
    ): BpmnValidationFailedEvent = BpmnValidationFailedEvent(
        request = request,
        xml = "<definitions />",
        diagnostics =
        listOf(
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.LINT,
                message = message,
                rule = "bpmner/act-verb-object-name",
            ),
        ),
        attemptNumber = 1,
        repairAttempts = 0,
        processId = processId,
    )
}
