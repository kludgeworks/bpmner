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

@Suppress("TooManyFunctions")
class ExpandAbbreviationsHandlerTest {
    private val handler = ExpandAbbreviationsHandler()
    private val defaultConfig = HandlerConfig(
        replacementMap = mapOf(
            "REQ" to "request",
            "RESP" to "response",
            "AUTH" to "authentication",
        ),
    )

    @Test
    fun `handler name matches Pkl repair handler field`() {
        assertEquals("expandAbbreviations", handler.handlerName)
    }

    @Test
    fun `expands single abbreviation`() {
        val ops = handler.buildPatch(definitionWithTask("Validate REQ"), "Task_1", defaultConfig)
        assertEquals(1, ops.size)
        val op = ops.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Validate request", op.name)
    }

    @Test
    fun `expands multiple abbreviations in one name`() {
        val ops = handler.buildPatch(definitionWithTask("AUTH REQ RESP"), "Task_1", defaultConfig)
        assertEquals("authentication request response", ops.single().name)
    }

    @Test
    fun `respects word boundaries`() {
        // "REQUIRED" must NOT match "REQ" — lookarounds keep the abbreviation whole-word only.
        val ops = handler.buildPatch(definitionWithTask("REQUIRED check"), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty(), "Substring of a longer word must not be expanded")
    }

    @Test
    fun `matches abbreviation followed by punctuation`() {
        // `\b` would fail to find a boundary after `Q.`; lookarounds match correctly.
        val ops = handler.buildPatch(definitionWithTask("Validate REQ."), "Task_1", defaultConfig)
        assertEquals("Validate request.", ops.single().name)
    }

    @Test
    fun `matches abbreviation followed by comma`() {
        val ops = handler.buildPatch(definitionWithTask("Process REQ, then RESP"), "Task_1", defaultConfig)
        assertEquals("Process request, then response", ops.single().name)
    }

    @Test
    fun `does not chain replacements when an expansion is also a key`() {
        // Sequential per-key fold would expand A → B → C; single-pass alternation stops at B.
        val chainConfig = HandlerConfig(replacementMap = mapOf("A" to "B", "B" to "C"))
        val ops = handler.buildPatch(definitionWithTask("word A"), "Task_1", chainConfig)
        assertEquals(
            "word B",
            ops.single().name,
            "Expansion 'B' must not be re-scanned as the key for 'C'",
        )
    }

    @Test
    fun `no ops when replacement map is empty`() {
        val ops = handler.buildPatch(definitionWithTask("Validate REQ"), "Task_1", HandlerConfig.EMPTY)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops when no abbreviation matches`() {
        val ops = handler.buildPatch(definitionWithTask("Validate input"), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for blank name`() {
        val ops = handler.buildPatch(definitionWithTask(""), "Task_1", defaultConfig)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `no ops for unknown element id`() {
        val ops = handler.buildPatch(definitionWithTask("Validate REQ"), "NotPresent", defaultConfig)
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
