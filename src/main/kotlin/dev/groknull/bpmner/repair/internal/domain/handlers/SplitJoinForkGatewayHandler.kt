package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import org.springframework.stereotype.Component

@Component
internal class SplitJoinForkGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "splitJoinForkGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val gateway = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        val incomingEdges = definition.sequences.filter { it.targetRef == elementId }
        val outgoingEdges = definition.sequences.filter { it.sourceRef == elementId }
        if (incomingEdges.size < 2 || outgoingEdges.size < 2) return emptyList()

        val joinId = TopologyGeometry.freshId("Gateway_join", definition)
        val joinEdgeId = TopologyGeometry.freshId("Flow_det", definition)
        val joinGw =
            BpmnNode(
                id = joinId,
                name = null,
                type = NodeType.EXCLUSIVE_GATEWAY,
                bounds =
                    BpmnBounds(
                        x = gateway.bounds.x - TopologyGeometry.JOIN_GATEWAY_X_OFFSET,
                        y = gateway.bounds.y,
                        width = TopologyGeometry.GATEWAY_SIZE,
                        height = TopologyGeometry.GATEWAY_SIZE,
                    ),
            )
        val joinCenter =
            BpmnWaypoint(
                joinGw.bounds.x + TopologyGeometry.GATEWAY_HALF_SIZE,
                joinGw.bounds.y + TopologyGeometry.GATEWAY_HALF_SIZE,
            )
        val forkEntry = BpmnWaypoint(gateway.bounds.x, gateway.bounds.y + TopologyGeometry.GATEWAY_HALF_SIZE)
        val joinToFork =
            BpmnEdge(
                id = joinEdgeId,
                sourceRef = joinId,
                targetRef = elementId,
                waypoints = listOf(joinCenter, forkEntry),
            )

        val ops = mutableListOf<BpmnPatchOperation>()
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_NODE, node = joinGw)
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_EDGE, edge = joinToFork)
        for (edge in incomingEdges) {
            val updated = edge.copy(targetRef = joinId, waypoints = listOf(edge.waypoints.first(), joinCenter))
            ops += BpmnPatchOperation(type = BpmnPatchOperationType.REPLACE_EDGE, edgeId = edge.id, edge = updated)
        }
        return ops
    }
}
