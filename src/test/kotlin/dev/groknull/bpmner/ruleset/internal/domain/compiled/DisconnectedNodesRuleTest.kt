/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
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
}
