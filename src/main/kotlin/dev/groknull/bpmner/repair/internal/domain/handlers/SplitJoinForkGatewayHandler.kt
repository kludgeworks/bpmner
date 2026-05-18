/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
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

        val joinId = TopologyIds.fresh("Gateway_join", definition)
        val joinEdgeId = TopologyIds.fresh("Flow_det", definition)
        val joinGw =
            BpmnNode(
                id = joinId,
                name = null,
                type = NodeType.EXCLUSIVE_GATEWAY,
            )
        val joinToFork =
            BpmnEdge(
                id = joinEdgeId,
                sourceRef = joinId,
                targetRef = gateway.id,
            )

        val ops = mutableListOf<BpmnPatchOperation>()
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_NODE, node = joinGw)
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_EDGE, edge = joinToFork)
        for (edge in incomingEdges) {
            val updated = edge.copy(targetRef = joinId)
            ops += BpmnPatchOperation(type = BpmnPatchOperationType.REPLACE_EDGE, edgeId = edge.id, edge = updated)
        }
        return ops
    }
}
