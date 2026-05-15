package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
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
                        BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(0.0, 0.0, 36.0, 36.0)),
                        BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(200.0, 0.0, 36.0, 36.0)),
                        BpmnNode("Task_1", "Do Work", NodeType.USER_TASK, BpmnBounds(70.0, -20.0, 100.0, 80.0)),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_2", "Task_1", "End_1", waypoints = emptyList()),
                        BpmnEdge("Flow_1", "Start_1", "Task_1", waypoints = emptyList()),
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
                        BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(0.0, 0.0, 36.0, 36.0)),
                        BpmnNode("Gateway_1", "Is Valid?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(100.0, 0.0, 50.0, 50.0)),
                        BpmnNode("Task_A", "A", NodeType.USER_TASK, BpmnBounds(200.0, -50.0, 100.0, 80.0)),
                        BpmnNode("Task_B", "B", NodeType.USER_TASK, BpmnBounds(200.0, 50.0, 100.0, 80.0)),
                        BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(400.0, 0.0, 36.0, 36.0)),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_Start", "Start_1", "Gateway_1", waypoints = emptyList()),
                        BpmnEdge("Flow_B", "Gateway_1", "Task_B", "No", "valid == false", waypoints = emptyList()),
                        BpmnEdge("Flow_A", "Gateway_1", "Task_A", "Yes", "valid == true", waypoints = emptyList()),
                        BpmnEdge("Flow_End_A", "Task_A", "End_1", waypoints = emptyList()),
                        BpmnEdge("Flow_End_B", "Task_B", "End_1", waypoints = emptyList()),
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
                        BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(0.0, 0.0, 36.0, 36.0)),
                        BpmnNode("Task_1", "Reachable", NodeType.USER_TASK, BpmnBounds(100.0, 0.0, 100.0, 80.0)),
                        BpmnNode("Task_Unreachable", "Unreachable", NodeType.USER_TASK, BpmnBounds(100.0, 200.0, 100.0, 80.0)),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "Start_1", "Task_1", waypoints = emptyList()),
                        BpmnEdge("Flow_Dangling", "Task_Unreachable", "Task_1", waypoints = emptyList()),
                    ),
            )

        val summary = summarizer.summarize(definition)

        assertEquals(listOf("Start_1", "Task_1", "Task_Unreachable"), summary.elements.map { it.id })
        assertEquals(listOf("Flow_1", "Flow_Dangling"), summary.flows.map { it.id })

        assertEquals(listOf("Task_Unreachable", "Flow_Dangling"), summary.unreachableElementIds)
    }
}
