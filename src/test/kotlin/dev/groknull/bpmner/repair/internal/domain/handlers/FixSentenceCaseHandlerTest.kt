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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
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
    fun `preserves trailing punctuation on acronym in non-initial position`() {
        // Old TS spec lowercased the acronym to `"aPI,"` because the trailing comma broke the
        // `^[A-Z]{2,}$` match. The widened regex tolerates non-alphabetic boundary chars.
        val ops = handler.buildPatch(definitionWithTask("Process API, then return"), "Task_1")
        assertTrue(ops.isEmpty(), "name with preserved acronym must already be sentence-case")
    }

    @Test
    fun `preserves acronym with trailing period when casing change is needed`() {
        val ops = handler.buildPatch(definitionWithTask("call BPMN. spec"), "Task_1")
        assertEquals("Call BPMN. spec", ops.single().name)
    }

    @Test
    fun `preserves multiple inner spaces`() {
        // Old TS spec collapsed runs of whitespace to a single space, emitting a SET_NODE_NAME
        // patch even when the casing was already correct. The Kotlin port leaves inner
        // whitespace untouched.
        val ops = handler.buildPatch(definitionWithTask("Approve  customer order"), "Task_1")
        assertTrue(ops.isEmpty(), "double-space inside an otherwise-correct name must not trigger a patch")
    }

    @Test
    fun `preserves tab as inner whitespace`() {
        val ops = handler.buildPatch(definitionWithTask("Approve\tcustomer order"), "Task_1")
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `preserves inner whitespace when a casing change is needed`() {
        // Force a casing change so we can assert the double space survives the transform.
        val ops = handler.buildPatch(definitionWithTask("approve  Customer order"), "Task_1")
        assertEquals("Approve  customer order", ops.single().name)
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
