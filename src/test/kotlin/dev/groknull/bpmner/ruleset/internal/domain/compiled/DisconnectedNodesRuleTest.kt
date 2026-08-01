/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DisconnectedNodesRuleTest {
    private val rule = DisconnectedNodesRule()

    @Test
    fun `reports a disconnected cycle but accepts one connected scope`() {
        val disconnected = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "P",
                nodes = listOf(
                    BpmnStartEvent("s"),
                    BpmnUserTask("a"),
                    BpmnEndEvent("e"),
                    BpmnUserTask("x"),
                    BpmnUserTask("y"),
                ),
                sequences = listOf(
                    BpmnEdge("f1", "s", "a"),
                    BpmnEdge("f2", "a", "e"),
                    BpmnEdge("f3", "x", "y"),
                    BpmnEdge("f4", "y", "x"),
                ),
            ),
        )

        assertEquals(
            listOf("process contains disconnected flow nodes: x, y"),
            rule.evaluate(disconnected).map { it.message },
        )
        assertTrue(
            rule.evaluate(
                BpmnDefinitionContext(
                    BpmnDefinition(
                        "P",
                        "P",
                        listOf(BpmnStartEvent("s"), BpmnEndEvent("e")),
                        listOf(BpmnEdge("f", "s", "e")),
                    ),
                ),
            ).isEmpty(),
        )
    }

    @Test
    fun `does not cross nested or sibling subprocess scopes`() {
        val context = BpmnDefinitionContext(
            BpmnDefinition(
                "P",
                "P",
                listOf(
                    BpmnStartEvent("start"),
                    BpmnSubProcess("first"),
                    BpmnSubProcess("second"),
                    BpmnEndEvent("end"),
                    BpmnStartEvent("first-start", parentRef = "first"),
                    BpmnEndEvent("first-end", parentRef = "first"),
                    BpmnSubProcess("nested", parentRef = "first"),
                    BpmnStartEvent("nested-start", parentRef = "nested"),
                    BpmnEndEvent("nested-end", parentRef = "nested"),
                    BpmnStartEvent("second-start", parentRef = "second"),
                    BpmnEndEvent("second-end", parentRef = "second"),
                ),
                listOf(
                    BpmnEdge("top-1", "start", "first"),
                    BpmnEdge("top-2", "first", "second"),
                    BpmnEdge("top-3", "second", "end"),
                    BpmnEdge("first-flow-1", "first-start", "nested", parentRef = "first"),
                    BpmnEdge("first-flow-2", "nested", "first-end", parentRef = "first"),
                    BpmnEdge("nested-flow", "nested-start", "nested-end", parentRef = "nested"),
                    BpmnEdge("second-flow", "second-start", "second-end", parentRef = "second"),
                ),
            ),
        )

        assertTrue(rule.evaluate(context).isEmpty())
    }

    @Test
    fun `accepts a boundary handler flow as a separate connected component`() {
        val context = BpmnDefinitionContext(
            BpmnDefinition(
                "P",
                "P",
                listOf(
                    BpmnStartEvent("start"),
                    BpmnUserTask("task"),
                    BpmnEndEvent("end"),
                    BpmnBoundaryEvent("boundary", attachedToRef = "task", eventDefinition = BpmnNoneEventDefinition),
                    BpmnUserTask("handler"),
                    BpmnEndEvent("handler-end"),
                ),
                listOf(
                    BpmnEdge("normal-1", "start", "task"),
                    BpmnEdge("normal-2", "task", "end"),
                    BpmnEdge("handler-1", "boundary", "handler"),
                    BpmnEdge("handler-2", "handler", "handler-end"),
                ),
            ),
        )

        assertTrue(rule.evaluate(context).isEmpty())
    }
}
