/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import kotlin.test.Test
import kotlin.test.assertEquals

class BpmnXmlToDefinitionConverterTest {
    private val forward = BpmnDefinitionToXmlConverter()
    private val reverse = BpmnXmlToDefinitionConverter()

    @Test
    fun `round-trip a simple linear process preserves nodes and sequences`() {
        val original = linearDefinition()

        val parsed = reverse.parse(forward.toXml(original))

        assertProcessShellEqual(original, parsed)
        assertEquals(original.nodes.byId(), parsed.nodes.byId())
        assertEquals(original.sequences.byId(), parsed.sequences.byId())
    }

    @Test
    fun `round-trip a branching process preserves nodes, sequences, names, and conditions`() {
        val original = branchingDefinition()

        val parsed = reverse.parse(forward.toXml(original))

        assertProcessShellEqual(original, parsed)
        assertEquals(original.nodes.byId(), parsed.nodes.byId())
        assertEquals(original.sequences.byId(), parsed.sequences.byId())
    }

    private fun assertProcessShellEqual(
        a: BpmnDefinition,
        b: BpmnDefinition,
    ) {
        assertEquals(a.processId, b.processId)
        assertEquals(a.processName, b.processName)
        assertEquals(a.nodes.size, b.nodes.size)
        assertEquals(a.sequences.size, b.sequences.size)
    }

    private fun List<BpmnNode>.byId(): Map<String, BpmnNode> = associateBy { it.id }

    @JvmName("edgesById")
    private fun List<BpmnEdge>.byId(): Map<String, BpmnEdge> = associateBy { it.id }

    private fun linearDefinition() =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Linear Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 100.0, 36.0, 36.0)),
                    BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(200.0, 80.0, 100.0, 80.0)),
                    BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(360.0, 100.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "Start_1",
                        "Task_1",
                        waypoints = listOf(BpmnWaypoint(116.0, 118.0), BpmnWaypoint(200.0, 120.0)),
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "Task_1",
                        "End_1",
                        waypoints = listOf(BpmnWaypoint(300.0, 120.0), BpmnWaypoint(360.0, 118.0)),
                    ),
                ),
        )

    private fun branchingDefinition() =
        BpmnDefinition(
            processId = "Process_2",
            processName = "Branching Process",
            nodes =
                listOf(
                    BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 140.0, 36.0, 36.0)),
                    BpmnNode(
                        "Gateway_1",
                        "Is valid?",
                        NodeType.EXCLUSIVE_GATEWAY,
                        BpmnBounds(180.0, 135.0, 50.0, 50.0),
                    ),
                    BpmnNode("Task_1", "Approve", NodeType.USER_TASK, BpmnBounds(300.0, 100.0, 100.0, 80.0)),
                    BpmnNode("Task_2", "Reject", NodeType.SERVICE_TASK, BpmnBounds(300.0, 200.0, 100.0, 80.0)),
                    BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(460.0, 140.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "Start_1",
                        "Gateway_1",
                        waypoints = listOf(BpmnWaypoint(116.0, 158.0), BpmnWaypoint(180.0, 160.0)),
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "Gateway_1",
                        "Task_1",
                        name = "Yes",
                        waypoints = listOf(BpmnWaypoint(205.0, 135.0), BpmnWaypoint(350.0, 140.0)),
                    ),
                    BpmnEdge(
                        "Flow_3",
                        "Gateway_1",
                        "Task_2",
                        name = "No",
                        conditionExpression = "\${value < 0}",
                        waypoints = listOf(BpmnWaypoint(205.0, 185.0), BpmnWaypoint(350.0, 240.0)),
                    ),
                    BpmnEdge(
                        "Flow_4",
                        "Task_1",
                        "End_1",
                        waypoints = listOf(BpmnWaypoint(400.0, 140.0), BpmnWaypoint(460.0, 158.0)),
                    ),
                    BpmnEdge(
                        "Flow_5",
                        "Task_2",
                        "End_1",
                        waypoints = listOf(BpmnWaypoint(400.0, 240.0), BpmnWaypoint(460.0, 158.0)),
                    ),
                ),
        )
}
