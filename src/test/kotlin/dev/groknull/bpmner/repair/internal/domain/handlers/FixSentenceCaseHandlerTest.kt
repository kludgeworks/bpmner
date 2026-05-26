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

class FixSentenceCaseHandlerTest {
    private val handler = FixSentenceCaseHandler()

    @Test
    fun `handler name matches Pkl repair handler field`() {
        assertEquals("fixSentenceCase", handler.handlerName)
    }

    @Test
    fun `lowercases non-initial words and capitalizes first`() {
        val ops = handler.buildPatch(definitionWithTask("Approve Customer Order"), "Task_1")
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Approve customer order", op.name)
    }

    @Test
    fun `preserves 2-plus uppercase acronyms in non-initial position`() {
        // Lowercase first word forces a real change so we can see the acronym ("ORDER") survive.
        val ops = handler.buildPatch(definitionWithTask("process ORDER details"), "Task_1")
        assertEquals("Process ORDER details", ops.single().name)
    }

    @Test
    fun `capitalizes first character of leading acronym word`() {
        // TS handler always capitalises the first char of word 0 regardless of acronym status.
        val ops = handler.buildPatch(definitionWithTask("api gateway request"), "Task_1")
        assertEquals("Api gateway request", ops.single().name)
    }

    @Test
    fun `idempotent on already-sentence-case name`() {
        val ops = handler.buildPatch(definitionWithTask("Approve customer order"), "Task_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for blank name`() {
        val ops = handler.buildPatch(definitionWithTask(""), "Task_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for unknown element id`() {
        val ops = handler.buildPatch(definitionWithTask("Bad Name"), "NotPresent")
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
