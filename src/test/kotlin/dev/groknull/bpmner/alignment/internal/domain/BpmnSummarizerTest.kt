/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnSummarizerTest {
    private val summarizer = BpmnSummarizer()

    @Test
    fun `summarizes linear process in traversal order`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Linear Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnEndEvent("End_1", "End"),
                    BpmnUserTask("Task_1", "Do Work"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_2", "Task_1", "End_1"),
                    BpmnEdge("Flow_1", "Start_1", "Task_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        assertEquals("Process_1", summary.processId)
        assertEquals("Linear Process", summary.processName)

        // Check element order: Start -> Task -> End
        assertEquals(listOf("Start_1", "Task_1", "End_1"), summary.elements.map { it.id })

        // Check flow order: Flow_1 (Start->Task) -> Flow_2 (Task->End)
        assertEquals(listOf("Flow_1", "Flow_2"), summary.flows.map { it.id })
        assertTrue(summary.unreachableElementIds.isEmpty())
    }

    @Test
    fun `summarizes branching process with stable ordering`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Branching Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnExclusiveGateway("Gateway_1", "Is Valid?"),
                    BpmnUserTask("Task_A", "A"),
                    BpmnUserTask("Task_B", "B"),
                    BpmnEndEvent("End_1", "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_Start", "Start_1", "Gateway_1"),
                    BpmnEdge("Flow_B", "Gateway_1", "Task_B", "No", "valid == false"),
                    BpmnEdge("Flow_A", "Gateway_1", "Task_A", "Yes", "valid == true"),
                    BpmnEdge("Flow_End_A", "Task_A", "End_1"),
                    BpmnEdge("Flow_End_B", "Task_B", "End_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        // BFS traversal:
        // Level 0: Start_1
        // Level 1: Gateway_1 (via Flow_Start)
        // Level 2: Task_A (via Flow_A), Task_B (via Flow_B) -- sorted by Flow ID (Flow_A < Flow_B)
        // Level 3: End_1 (via Flow_End_A)

        assertEquals(listOf("Start_1", "Gateway_1", "Task_A", "Task_B", "End_1"), summary.elements.map { it.id })
        assertEquals(listOf("Flow_Start", "Flow_A", "Flow_B", "Flow_End_A", "Flow_End_B"), summary.flows.map { it.id })

        val flowA = summary.flows.find { it.id == "Flow_A" }!!
        assertEquals("Yes", flowA.name)
        assertEquals("valid == true", flowA.conditionExpression)
    }

    @Test
    fun `detects unreachable elements`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Broken Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnUserTask("Task_1", "Reachable"),
                    BpmnUserTask("Task_Unreachable", "Unreachable"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "Start_1", "Task_1"),
                    BpmnEdge("Flow_Dangling", "Task_Unreachable", "Task_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        assertEquals(listOf("Start_1", "Task_1", "Task_Unreachable"), summary.elements.map { it.id })
        assertEquals(listOf("Flow_1", "Flow_Dangling"), summary.flows.map { it.id })

        assertEquals(listOf("Task_Unreachable", "Flow_Dangling"), summary.unreachableElementIds)
    }

    @Test
    fun `subprocess children reachable from their own inner start are not flagged unreachable`() {
        // The subprocess marker is reached via the parent-level flow; its children are reached via
        // the subprocess's own inner start event. Neither should appear as unreachable.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Subprocess Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_top", "Start"),
                    BpmnSubProcess("SubProcess_1", "Handle"),
                    BpmnEndEvent("End_top", "Done"),
                    BpmnStartEvent("Start_in", "Begin", parentRef = "SubProcess_1"),
                    BpmnUserTask("Task_in", "Work", parentRef = "SubProcess_1"),
                    BpmnEndEvent("End_in", "Inner done", parentRef = "SubProcess_1"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_t1", "Start_top", "SubProcess_1"),
                    BpmnEdge("Flow_t2", "SubProcess_1", "End_top"),
                    BpmnEdge("Flow_i1", "Start_in", "Task_in", parentRef = "SubProcess_1"),
                    BpmnEdge("Flow_i2", "Task_in", "End_in", parentRef = "SubProcess_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        assertTrue(
            summary.unreachableElementIds.isEmpty(),
            "subprocess children are reachable via the inner start; got: ${summary.unreachableElementIds}",
        )
    }

    @Test
    fun `event subprocess marker with no connecting flow is not reported unreachable`() {
        // The event-subprocess marker has no incoming edge by design — it is a reachability root.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Event Subprocess Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_main", "Start"),
                    BpmnEndEvent("End_main", "Done"),
                    BpmnSubProcess("EventSub_1", "Handle", triggeredByEvent = true),
                    BpmnStartEvent("Start_evt", "Triggered", parentRef = "EventSub_1"),
                    BpmnUserTask("Task_evt", "Handle", parentRef = "EventSub_1"),
                    BpmnEndEvent("End_evt", "Handled", parentRef = "EventSub_1"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_main", "Start_main", "End_main"),
                    BpmnEdge("Flow_e1", "Start_evt", "Task_evt", parentRef = "EventSub_1"),
                    BpmnEdge("Flow_e2", "Task_evt", "End_evt", parentRef = "EventSub_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        assertTrue(
            summary.unreachableElementIds.isEmpty(),
            "the event-subprocess marker is a root and its inner nodes reachable; got: ${summary.unreachableElementIds}",
        )
    }
}
