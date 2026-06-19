/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnEdge
import dev.groknull.bpmner.domain.BpmnEndEvent
import dev.groknull.bpmner.domain.BpmnStartEvent
import dev.groknull.bpmner.domain.BpmnUserTask
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import dev.groknull.bpmner.rules.BpmnerLintConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StripTypeWordsHandlerTest {
    private val handler = StripTypeWordsHandler(BpmnerLintConfig())
    private val defaultConfig = HandlerConfig.EMPTY

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
    fun `ignores per-diagnostic staticConfig and uses injected conventions`() {
        val ops = handler.buildPatch(definitionWithTask("Approve Order Activity"), "Task_1", HandlerConfig.EMPTY)
        assertEquals("Approve Order", ops.single().name)
    }

    @Test
    fun `no ops when injected elementTypeWords list is empty`() {
        val emptyHandler = StripTypeWordsHandler(BpmnerLintConfig(elementTypeWords = emptyList()))
        val ops = emptyHandler.buildPatch(definitionWithTask("Approve Order Activity"), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `custom injected elementTypeWords are used`() {
        val customHandler = StripTypeWordsHandler(BpmnerLintConfig(elementTypeWords = listOf("step")))
        val ops = customHandler.buildPatch(definitionWithTask("Approve Order Step"), "Task_1", defaultConfig)
        assertEquals("Approve Order", ops.single().name)
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
