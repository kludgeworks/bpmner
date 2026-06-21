/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DanglingEdgeRuleTest {
    private val rule = DanglingEdgeRule()

    @Test
    fun `dangling sourceRef emits def-dangling-source`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "missing", targetRef = "e")),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("def-dangling-source", diag.diagnosticCode)
        assertEquals("def-dangling-edges", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("edge f1 sourceRef 'missing' does not match any node id", diag.message)
        assertEquals("f1", diag.elementId)
    }

    @Test
    fun `dangling targetRef emits def-dangling-target`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "missing")),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("def-dangling-target", diag.diagnosticCode)
        assertEquals("edge f1 targetRef 'missing' does not match any node id", diag.message)
    }

    @Test
    fun `self-reference emits def-self-reference`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnUserTask(id = "t"), BpmnEndEvent(id = "e")),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f-self", sourceRef = "t", targetRef = "t"),
                        BpmnEdge(id = "f-start", sourceRef = "s", targetRef = "t"),
                        BpmnEdge(id = "f-end", sourceRef = "t", targetRef = "e"),
                    ),
                ),
            )

        val selfRef = rule.evaluate(ctx).single { it.diagnosticCode == "def-self-reference" }
        assertEquals("edge f-self must not self-reference source and target", selfRef.message)
        assertEquals("f-self", selfRef.elementId)
    }

    @Test
    fun `clean definition emits no diagnostics`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnUserTask(id = "t"), BpmnEndEvent(id = "e")),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "t"),
                        BpmnEdge(id = "f2", sourceRef = "t", targetRef = "e"),
                    ),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }
}
