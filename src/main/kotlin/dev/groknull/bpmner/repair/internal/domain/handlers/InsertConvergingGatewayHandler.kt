package dev.groknull.bpmner.repair.internal.domain.handlers
import dev.groknull.bpmner.core.BpmnDefinition


import org.springframework.stereotype.Component

@Component
internal class InsertConvergingGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "insertConvergingGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val task = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        if (task.type !in setOf(NodeType.USER_TASK, NodeType.SERVICE_TASK)) return emptyList()
        val incomingEdges = definition.sequences.filter { it.targetRef == elementId }
        if (incomingEdges.size < 2) return emptyList()

        val joinId = TopologyGeometry.freshId("Gateway_join", definition)
        val joinEdgeId = TopologyGeometry.freshId("Flow_det", definition)
        val joinGw =
            BpmnNode(
                id = joinId,
                name = null,
                type = NodeType.EXCLUSIVE_GATEWAY,
                bounds =
                    BpmnBounds(
                        x = task.bounds.x - TopologyGeometry.JOIN_GATEWAY_X_OFFSET,
                        y = task.bounds.y + (task.bounds.height / 2.0) - TopologyGeometry.GATEWAY_HALF_SIZE,
                        width = TopologyGeometry.GATEWAY_SIZE,
                        height = TopologyGeometry.GATEWAY_SIZE,
                    ),
            )
        val joinCenter =
            BpmnWaypoint(
                joinGw.bounds.x + TopologyGeometry.GATEWAY_HALF_SIZE,
                joinGw.bounds.y + TopologyGeometry.GATEWAY_HALF_SIZE,
            )
        val taskEntry = BpmnWaypoint(task.bounds.x, task.bounds.y + task.bounds.height / 2.0)
        val joinToTask =
            BpmnEdge(
                id = joinEdgeId,
                sourceRef = joinId,
                targetRef = elementId,
                waypoints = listOf(joinCenter, taskEntry),
            )

        val ops = mutableListOf<BpmnPatchOperation>()
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_NODE, node = joinGw)
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_EDGE, edge = joinToTask)
        for (edge in incomingEdges) {
            val updated = edge.copy(targetRef = joinId, waypoints = listOf(edge.waypoints.first(), joinCenter))
            ops += BpmnPatchOperation(type = BpmnPatchOperationType.REPLACE_EDGE, edgeId = edge.id, edge = updated)
        }
        return ops
    }
}
