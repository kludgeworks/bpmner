/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BpmnNodeTransparencyTest {
    @Test
    fun `unnamed exclusive gateway with one outbound is transparent`() {
        val node = BpmnExclusiveGateway("Gateway_merge", name = null)
        val outgoing =
            mapOf(
                "Gateway_merge" to listOf(BpmnEdge("F1", "Gateway_merge", "Task_next")),
            )
        assertTrue(node.isSemanticallyTransparent(outgoing))
    }

    @Test
    fun `unnamed parallel gateway with one outbound is transparent`() {
        val node = BpmnParallelGateway("Gateway_join", name = null)
        val outgoing =
            mapOf(
                "Gateway_join" to listOf(BpmnEdge("F1", "Gateway_join", "Task_next")),
            )
        assertTrue(node.isSemanticallyTransparent(outgoing))
    }

    @Test
    fun `named exclusive gateway is opaque even with one outbound`() {
        val node = BpmnExclusiveGateway("Gateway_named", name = "Decide path")
        val outgoing =
            mapOf(
                "Gateway_named" to listOf(BpmnEdge("F1", "Gateway_named", "Task_next")),
            )
        assertFalse(node.isSemanticallyTransparent(outgoing))
    }

    @Test
    fun `unnamed gateway with multiple outbounds is opaque`() {
        val node = BpmnExclusiveGateway("Gateway_fork", name = null)
        val outgoing =
            mapOf(
                "Gateway_fork" to
                    listOf(
                        BpmnEdge("F1", "Gateway_fork", "Task_a"),
                        BpmnEdge("F2", "Gateway_fork", "Task_b"),
                    ),
            )
        assertFalse(node.isSemanticallyTransparent(outgoing))
    }

    @Test
    fun `unnamed gateway with zero outbounds is opaque`() {
        val node = BpmnExclusiveGateway("Gateway_dead", name = null)
        assertFalse(node.isSemanticallyTransparent(emptyMap()))
    }

    @Test
    fun `task is opaque`() {
        val task: BpmnNode = BpmnUserTask("Task_1", "Do thing")
        val outgoing =
            mapOf("Task_1" to listOf(BpmnEdge("F1", "Task_1", "Task_next")))
        assertFalse(task.isSemanticallyTransparent(outgoing))
        val service: BpmnNode = BpmnServiceTask("Task_2", "Call API")
        assertFalse(service.isSemanticallyTransparent(outgoing))
    }

    @Test
    fun `start and end events are opaque`() {
        val start: BpmnNode = BpmnStartEvent("Start_1", "Begin")
        val end: BpmnNode = BpmnEndEvent("End_1", "Done")
        assertFalse(start.isSemanticallyTransparent(emptyMap()))
        assertFalse(end.isSemanticallyTransparent(emptyMap()))
    }
}
