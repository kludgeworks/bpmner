/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeleteIncomingFlowsHandlerTest {
    private val handler = DeleteIncomingFlowsHandler()

    @Test
    fun `handler name matches Pkl repair handler field`() {
        assertEquals("deleteIncomingFlows", handler.handlerName)
    }

    @Test
    fun `removes single incoming edge into start event`() {
        val ops = handler.buildPatch(startWithIncoming(incomingCount = 1), "Start_1")
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.REMOVE_EDGE, op.type)
        assertEquals("Flow_in_0", op.edgeId)
    }

    @Test
    fun `removes every incoming edge when there are multiple`() {
        val ops = handler.buildPatch(startWithIncoming(incomingCount = 3), "Start_1")
        assertEquals(3, ops.size)
        assertTrue(ops.all { it.type == BpmnPatchOperationType.REMOVE_EDGE })
        val edgeIds = ops.mapNotNull { it.edgeId }.toSet()
        assertEquals(setOf("Flow_in_0", "Flow_in_1", "Flow_in_2"), edgeIds)
    }

    @Test
    fun `no ops when start event has no incoming flows`() {
        val ops = handler.buildPatch(startWithIncoming(incomingCount = 0), "Start_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for unknown element id`() {
        val ops = handler.buildPatch(startWithIncoming(incomingCount = 2), "NotPresent")
        assertTrue(ops.isEmpty())
    }

    private fun startWithIncoming(incomingCount: Int): BpmnDefinition {
        val upstreamTasks = (0 until incomingCount).map { idx -> BpmnUserTask("Upstream_$idx", "Up $idx") }
        val incomingEdges =
            (0 until incomingCount).map { idx ->
                BpmnEdge("Flow_in_$idx", "Upstream_$idx", "Start_1")
            }
        return BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes =
            listOf(
                BpmnStartEvent("Start_1", "Start"),
                BpmnUserTask("Task_1", "Continue"),
                BpmnEndEvent("End_1", "End"),
            ) + upstreamTasks,
            sequences =
            incomingEdges +
                listOf(
                    BpmnEdge("Flow_out_1", "Start_1", "Task_1"),
                    BpmnEdge("Flow_out_2", "Task_1", "End_1"),
                ),
        )
    }
}
