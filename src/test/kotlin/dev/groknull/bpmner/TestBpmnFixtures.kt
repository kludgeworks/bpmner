package dev.groknull.bpmner

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.ValidatedOutline

/**
 * Shared test fixtures for BPMN definitions and graphs to prevent duplication.
 */
object TestBpmnFixtures {
    /**
     * Creates a standard three-node BPMN definition (Start -> Task -> End).
     */
    fun testBpmnDefinition(
        processId: String = "Process_MakeToast",
        processName: String = "Make toast",
        nodeType: NodeType = NodeType.SERVICE_TASK,
        startName: String = "Order received",
        taskName: String = "Toast bread",
        endName: String = "Toast served",
    ) = BpmnDefinition(
        processId = processId,
        processName = processName,
        nodes =
            listOf(
                BpmnNode("StartEvent_1", startName, NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
                BpmnNode("Task_1", taskName, nodeType, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
                BpmnNode("EndEvent_1", endName, NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
            ),
        sequences =
            listOf(
                BpmnEdge(
                    "Flow_1",
                    "StartEvent_1",
                    "Task_1",
                    waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
                ),
                BpmnEdge(
                    "Flow_2",
                    "Task_1",
                    "EndEvent_1",
                    waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
                ),
            ),
    )

    /**
     * Creates a [LaidOutProcessGraph] for the given definition.
     */
    fun testLaidOutGraph(
        definition: BpmnDefinition,
        request: BpmnRequest = BpmnRequest("test"),
        withOwnership: Boolean = false,
    ): LaidOutProcessGraph {
        val owner = if (withOwnership) "phase:main" else null
        val objectOwners =
            buildMap {
                if (owner != null) {
                    put("process", owner)
                    definition.nodes.forEach { put("nodes[id=${it.id}]", owner) }
                    definition.sequences.forEach { put("sequences[id=${it.id}]", owner) }
                }
            }
        val composed =
            ComposedProcessGraph(
                outline = ValidatedOutline(ProcessOutline(request, definition, OutlineMetrics(1, 0, 0, 0))),
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        val elementOwners =
            buildMap {
                if (owner != null) {
                    put(definition.processId, owner)
                    definition.nodes.forEach {
                        put(it.id, owner)
                        put("${it.id}_di", owner)
                    }
                    definition.sequences.forEach {
                        put(it.id, owner)
                        put("${it.id}_di", owner)
                    }
                }
            }
        return LaidOutProcessGraph(OwnedElementGraph(composed, elementOwners, objectOwners), definition)
    }
}
