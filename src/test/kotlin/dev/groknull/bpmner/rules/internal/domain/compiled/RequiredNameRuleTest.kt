/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequiredNameRuleTest {
    private val rule = RequiredNameRule()

    @Test
    fun `task without name emits def-missing-name`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnUserTask(id = "task"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "task"),
                        BpmnEdge(id = "f2", sourceRef = "task", targetRef = "e"),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-name", diag.diagnosticCode)
        assertEquals("def-required-names", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("task", diag.elementId)
    }

    @Test
    fun `start event without name emits def-missing-name`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e", name = "Done")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e")),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-missing-name", diag.diagnosticCode)
        assertEquals("s", diag.elementId)
    }

    @Test
    fun `diverging exclusive gateway without name emits def-missing-name`() {
        // Exclusive gateway with >1 outgoing requires a name (it's a decision question).
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnExclusiveGateway(id = "gw"),
                        BpmnUserTask(id = "a", name = "Approve"),
                        BpmnUserTask(id = "b", name = "Reject"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "gw"),
                        BpmnEdge(id = "f2", sourceRef = "gw", targetRef = "a"),
                        BpmnEdge(id = "f3", sourceRef = "gw", targetRef = "b"),
                        BpmnEdge(id = "f4", sourceRef = "a", targetRef = "e"),
                        BpmnEdge(id = "f5", sourceRef = "b", targetRef = "e"),
                    ),
                ),
            )

        val gwDiag = rule.evaluate(ctx).single { it.elementId == "gw" }
        assertEquals("def-missing-name", gwDiag.diagnosticCode)
    }

    @Test
    fun `converging exclusive gateway without name is allowed`() {
        // Exclusive gateway with exactly 1 outgoing (a join/merge) is allowed to be unnamed.
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnUserTask(id = "a", name = "Approve"),
                        BpmnUserTask(id = "b", name = "Reject"),
                        BpmnExclusiveGateway(id = "gw"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "a"),
                        BpmnEdge(id = "f2", sourceRef = "s", targetRef = "b"),
                        BpmnEdge(id = "f3", sourceRef = "a", targetRef = "gw"),
                        BpmnEdge(id = "f4", sourceRef = "b", targetRef = "gw"),
                        BpmnEdge(id = "f5", sourceRef = "gw", targetRef = "e"),
                    ),
                ),
            )

        assertTrue(rule.evaluate(ctx).none { it.elementId == "gw" })
    }

    @Test
    fun `parallel gateway without name is always allowed`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s", name = "Started"),
                        BpmnParallelGateway(id = "fork"),
                        BpmnUserTask(id = "a", name = "Task A"),
                        BpmnUserTask(id = "b", name = "Task B"),
                        BpmnParallelGateway(id = "join"),
                        BpmnEndEvent(id = "e", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "fork"),
                        BpmnEdge(id = "f2", sourceRef = "fork", targetRef = "a"),
                        BpmnEdge(id = "f3", sourceRef = "fork", targetRef = "b"),
                        BpmnEdge(id = "f4", sourceRef = "a", targetRef = "join"),
                        BpmnEdge(id = "f5", sourceRef = "b", targetRef = "join"),
                        BpmnEdge(id = "f6", sourceRef = "join", targetRef = "e"),
                    ),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }
}
