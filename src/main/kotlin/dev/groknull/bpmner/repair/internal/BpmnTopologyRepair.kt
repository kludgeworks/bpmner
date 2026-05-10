package dev.groknull.bpmner.repair.internal

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnTopologyRepair {

    private val logger = LoggerFactory.getLogger(BpmnTopologyRepair::class.java)

    fun buildTopologyPatch(definition: BpmnDefinition, diagnostics: List<BpmnDiagnostic>): BpmnRepairPatch? {
        val diag = diagnostics.firstOrNull { it.isTopologyRule() } ?: return null
        val nodeId = diag.elementId ?: return null
        val rule = diag.rule ?: return null

        val ops = when {
            rule.contains("no-gateway-join-fork") -> buildJoinForkRepair(nodeId, definition)
            rule.contains("fake-join") -> buildFakeJoinRepair(nodeId, definition)
            rule.contains("superfluous-gateway") -> buildSuperfluousGatewayRepair(nodeId, definition)
            rule.contains("gtw-02") -> buildConvergingGatewayClearName(nodeId, definition)
            else -> null
        }
        if (ops.isNullOrEmpty()) return null

        logger.info("Built deterministic topology patch: rule={}, elementId={}, ops={}", rule, nodeId, ops.size)
        return BpmnRepairPatch(operations = ops, reason = "Deterministic topology repair: $rule on $nodeId")
    }

    private fun buildJoinForkRepair(nodeId: String, definition: BpmnDefinition): List<BpmnPatchOperation>? {
        val gateway = definition.nodes.firstOrNull { it.id == nodeId } ?: return null
        val incomingEdges = definition.sequences.filter { it.targetRef == nodeId }
        val outgoingEdges = definition.sequences.filter { it.sourceRef == nodeId }
        if (incomingEdges.size < 2 || outgoingEdges.size < 2) return null

        val joinId = freshId("Gateway_join", definition)
        val joinEdgeId = freshId("Flow_det", definition)
        val joinGw = BpmnNode(
            id = joinId, name = null, type = NodeType.EXCLUSIVE_GATEWAY,
            bounds = BpmnBounds(x = gateway.bounds.x - 80.0, y = gateway.bounds.y, width = 50.0, height = 50.0),
        )
        val joinCenter = BpmnWaypoint(joinGw.bounds.x + 25.0, joinGw.bounds.y + 25.0)
        val forkEntry = BpmnWaypoint(gateway.bounds.x, gateway.bounds.y + 25.0)
        val joinToFork = dev.groknull.bpmner.core.BpmnEdge(
            id = joinEdgeId, sourceRef = joinId, targetRef = nodeId,
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

    private fun buildFakeJoinRepair(nodeId: String, definition: BpmnDefinition): List<BpmnPatchOperation>? {
        val task = definition.nodes.firstOrNull { it.id == nodeId } ?: return null
        val incomingEdges = definition.sequences.filter { it.targetRef == nodeId }
        if (incomingEdges.size < 2) return null

        val joinId = freshId("Gateway_join", definition)
        val joinEdgeId = freshId("Flow_det", definition)
        val joinGw = BpmnNode(
            id = joinId, name = null, type = NodeType.EXCLUSIVE_GATEWAY,
            bounds = BpmnBounds(
                x = task.bounds.x - 80.0, y = task.bounds.y + (task.bounds.height / 2.0) - 25.0,
                width = 50.0, height = 50.0,
            ),
        )
        val joinCenter = BpmnWaypoint(joinGw.bounds.x + 25.0, joinGw.bounds.y + 25.0)
        val taskEntry = BpmnWaypoint(task.bounds.x, task.bounds.y + task.bounds.height / 2.0)
        val joinToTask = dev.groknull.bpmner.core.BpmnEdge(
            id = joinEdgeId, sourceRef = joinId, targetRef = nodeId,
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

    private fun buildSuperfluousGatewayRepair(nodeId: String, definition: BpmnDefinition): List<BpmnPatchOperation>? {
        val incomingEdge = definition.sequences.singleOrNull { it.targetRef == nodeId } ?: return null
        val outgoingEdge = definition.sequences.singleOrNull { it.sourceRef == nodeId } ?: return null
        val updatedIncoming = incomingEdge.copy(
            targetRef = outgoingEdge.targetRef,
            waypoints = listOf(incomingEdge.waypoints.first(), outgoingEdge.waypoints.last()),
        )
        return listOf(
            BpmnPatchOperation(type = BpmnPatchOperationType.REPLACE_EDGE, edgeId = incomingEdge.id, edge = updatedIncoming),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_EDGE, edgeId = outgoingEdge.id),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_NODE, nodeId = nodeId),
        )
    }

    private fun buildConvergingGatewayClearName(nodeId: String, definition: BpmnDefinition): List<BpmnPatchOperation>? {
        val node = definition.nodes.firstOrNull { it.id == nodeId } ?: return null
        if (node.name.isNullOrBlank()) return null
        val incoming = definition.sequences.count { it.targetRef == nodeId }
        val outgoing = definition.sequences.count { it.sourceRef == nodeId }
        if (incoming <= 1 || outgoing > 1) return null
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = nodeId, name = null))
    }

    private fun freshId(prefix: String, definition: BpmnDefinition): String {
        val taken = (definition.nodes.map { it.id } + definition.sequences.map { it.id }).toSet()
        var n = 1
        while ("${prefix}_$n" in taken) n++
        return "${prefix}_$n"
    }

    private fun BpmnDiagnostic.isTopologyRule(): Boolean =
        source == BpmnDiagnosticSource.LINT && rule != null &&
            TOPOLOGY_LINT_RULES.any { rule.contains(it) }

    companion object {
        private val TOPOLOGY_LINT_RULES = listOf("no-gateway-join-fork", "fake-join", "superfluous-gateway", "gtw-02")
    }
}
