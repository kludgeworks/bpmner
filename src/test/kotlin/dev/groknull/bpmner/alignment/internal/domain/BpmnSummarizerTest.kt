/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnUserTask
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
    fun `splices synthetic join gateways out of elements and flows but keeps forking decisions`() {
        // A forking exclusive gateway (a named decision) merges back through an unnamed join
        // gateway. bpmnlint's `label-required` (and our BpmnNodeNamingPolicy) treat the join as
        // structural scaffolding needing no name, so the alignment summary must omit it entirely —
        // both from the element list AND the flows (rewired A->B) — otherwise the LLM aligner flags
        // the unnamed join as UNSUPPORTED (as an "unlisted joining gateway") and fails the run.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Join Process",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnExclusiveGateway("dec-1", "Is valid?"),
                    BpmnUserTask("Task_A", "A"),
                    BpmnUserTask("Task_B", "B"),
                    BpmnExclusiveGateway("Gateway_join_1", null),
                    BpmnEndEvent("End_1", "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_Start", "Start_1", "dec-1"),
                    BpmnEdge("Flow_A", "dec-1", "Task_A", "Yes", "valid == true"),
                    BpmnEdge("Flow_B", "dec-1", "Task_B", "No", "valid == false"),
                    BpmnEdge("Flow_JA", "Task_A", "Gateway_join_1"),
                    BpmnEdge("Flow_JB", "Task_B", "Gateway_join_1"),
                    BpmnEdge("Flow_End", "Gateway_join_1", "End_1"),
                ),
            )

        val summary = summarizer.summarize(definition)

        // The forking decision stays; the unnamed 2-in/1-out join is dropped from the element list.
        assertTrue(summary.elements.any { it.id == "dec-1" }, "forking decision gateway must be kept")
        assertEquals(
            listOf("Start_1", "dec-1", "Task_A", "Task_B", "End_1"),
            summary.elements.map { it.id },
        )
        // The join id must not appear anywhere in the flows — neither as source nor target.
        val flowEndpoints = summary.flows.map { "${it.sourceRef}->${it.targetRef}" }
        assertTrue(
            summary.flows.none { it.sourceRef == "Gateway_join_1" || it.targetRef == "Gateway_join_1" },
            "join gateway must be spliced out of the flow topology, got: $flowEndpoints",
        )
        // The two branch flows are rewired straight to the join's downstream target (End_1),
        // preserving connectivity so the aligner still sees both branches reach the end.
        assertTrue(
            summary.flows.any { it.sourceRef == "Task_A" && it.targetRef == "End_1" },
            "Task_A should be rewired directly to End_1",
        )
        assertTrue(
            summary.flows.any { it.sourceRef == "Task_B" && it.targetRef == "End_1" },
            "Task_B should be rewired directly to End_1",
        )
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
