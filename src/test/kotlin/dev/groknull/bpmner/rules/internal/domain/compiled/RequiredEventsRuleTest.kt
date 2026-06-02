/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequiredEventsRuleTest {
    private val rule = RequiredEventsRule()

    @Test
    fun `missing start event emits def-missing-start-event`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnUserTask(id = "t"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "t", targetRef = "e")),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("def-missing-start-event", diag.diagnosticCode)
        assertEquals("def-required-events", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("definition must contain at least one START_EVENT", diag.message)
        assertNull(diag.elementId, "Process-scoped diagnostic carries no elementId")
    }

    @Test
    fun `missing end event emits def-missing-end-event`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnUserTask(id = "t")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "t")),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        assertEquals("def-missing-end-event", diagnostics.single().diagnosticCode)
        assertEquals("definition must contain at least one END_EVENT", diagnostics.single().message)
    }

    @Test
    fun `clean definition with start and end events emits no diagnostics`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e")),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }

    @Test
    fun `start and end events nested in a subprocess do not satisfy the top-level requirement`() {
        // The only start/end live inside the subprocess (parentRef set), so the top-level process
        // still lacks both — the rule must flag them.
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnSubProcess(id = "sp"),
                        BpmnStartEvent(id = "s", parentRef = "sp"),
                        BpmnUserTask(id = "t", parentRef = "sp"),
                        BpmnEndEvent(id = "e", parentRef = "sp"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "t", parentRef = "sp"),
                        BpmnEdge(id = "f2", sourceRef = "t", targetRef = "e", parentRef = "sp"),
                    ),
                ),
            )

        val codes = rule.evaluate(ctx).map { it.diagnosticCode }.toSet()

        assertEquals(setOf("def-missing-start-event", "def-missing-end-event"), codes)
    }
}
