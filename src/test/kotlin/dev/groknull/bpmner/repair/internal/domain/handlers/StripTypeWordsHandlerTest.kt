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
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StripTypeWordsHandlerTest {
    private val handler = StripTypeWordsHandler()
    private val defaultConfig = HandlerConfig(staticConfig = mapOf("discouragedWords" to listOf("activity", "process", "event")))

    @Test
    fun `handler name matches Pkl repair handler field`() {
        assertEquals("stripTypeWords", handler.handlerName)
    }

    @Test
    fun `strips configured words case-insensitively`() {
        val ops = handler.buildPatch(definitionWithTask("Approve Order Activity"), "Task_1", defaultConfig)
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Approve Order", op.name)
    }

    @Test
    fun `collapses double spaces and trims after removal`() {
        val ops = handler.buildPatch(definitionWithTask("Customer process review"), "Task_1", defaultConfig)
        assertEquals("Customer review", ops.single().name)
    }

    @Test
    fun `no ops when stripping would leave empty name`() {
        val ops = handler.buildPatch(definitionWithTask("Activity"), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops when no discouraged word appears`() {
        val ops = handler.buildPatch(definitionWithTask("Approve order"), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops when staticConfig is missing`() {
        val ops = handler.buildPatch(definitionWithTask("Approve Order Activity"), "Task_1", HandlerConfig.EMPTY)
        assertTrue(ops.isEmpty(), "Missing config must be a no-op, not silently use a default")
    }

    @Test
    fun `no ops when discouragedWords list is empty`() {
        val emptyConfig = HandlerConfig(staticConfig = mapOf("discouragedWords" to emptyList<String>()))
        val ops = handler.buildPatch(definitionWithTask("Approve Order Activity"), "Task_1", emptyConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for unknown element id`() {
        val ops = handler.buildPatch(definitionWithTask("Bad Name Activity"), "NotPresent", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    private fun definitionWithTask(taskName: String) = BpmnDefinition(
        processId = "Process_1",
        processName = "Test",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", taskName),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1"),
            BpmnEdge("Flow_2", "Task_1", "End_1"),
        ),
    )
}
