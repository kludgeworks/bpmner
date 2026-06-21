/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClearNameHandlerTest {
    private val handler = ClearNameHandler()

    @Test
    fun `handler name matches Pkl repair handler field`() {
        assertEquals("clearName", handler.handlerName)
    }

    @Test
    fun `clears non-empty name`() {
        val ops = handler.buildPatch(definitionWithGateway(gatewayName = "Decide?"), "Gateway_1")
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Gateway_1", op.nodeId)
        assertNull(op.name, "Cleared name must be null, not empty string")
    }

    @Test
    fun `idempotent on already-blank name`() {
        val ops = handler.buildPatch(definitionWithGateway(gatewayName = null), "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `idempotent on whitespace-only name`() {
        val ops = handler.buildPatch(definitionWithGateway(gatewayName = "   "), "Gateway_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for unknown element id`() {
        val ops = handler.buildPatch(definitionWithGateway(gatewayName = "Decide?"), "NotPresent")
        assertTrue(ops.isEmpty())
    }

    private fun definitionWithGateway(gatewayName: String?) = BpmnDefinition(
        processId = "Process_1",
        processName = "Test",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnExclusiveGateway("Gateway_1", gatewayName),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1"),
            BpmnEdge("Flow_2", "Gateway_1", "End_1"),
        ),
    )
}
