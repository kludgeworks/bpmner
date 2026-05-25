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
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DuplicateIdRuleTest {
    private val rule = DuplicateIdRule()

    @Test
    fun `duplicate node id emits def-duplicate-node-id`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                        listOf(
                            BpmnStartEvent(id = "s"),
                            BpmnUserTask(id = "dup"),
                            BpmnUserTask(id = "dup"),
                            BpmnEndEvent(id = "e"),
                        ),
                    sequences =
                        listOf(
                            BpmnEdge(id = "f1", sourceRef = "s", targetRef = "dup"),
                            BpmnEdge(id = "f2", sourceRef = "dup", targetRef = "e"),
                        ),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("def-duplicate-node-id", diag.diagnosticCode)
        assertEquals("def-duplicate-ids", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("duplicate node id: dup", diag.message)
        assertEquals("dup", diag.elementId)
    }

    @Test
    fun `duplicate edge id emits def-duplicate-edge-id`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences =
                        listOf(
                            BpmnEdge(id = "dup", sourceRef = "s", targetRef = "e"),
                            BpmnEdge(id = "dup", sourceRef = "s", targetRef = "e"),
                        ),
                ),
            )

        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("def-duplicate-edge-id", diag.diagnosticCode)
        assertEquals("duplicate edge id: dup", diag.message)
        assertEquals("dup", diag.elementId)
    }

    @Test
    fun `blank trimmed ids are ignored (validator parity behavior)`() {
        // Both BpmnStartEvent and BpmnEndEvent have @field:NotBlank constraints, so genuinely
        // blank node ids can't be constructed; this guards the trim()-then-filter path against
        // whitespace-only ids on edges where the constraint is weaker.
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
