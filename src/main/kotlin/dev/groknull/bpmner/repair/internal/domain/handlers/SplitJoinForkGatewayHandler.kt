/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnParallelGateway
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import org.springframework.stereotype.Component

@Component
internal class SplitJoinForkGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "splitJoinForkGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val gateway = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        val incomingEdges = definition.sequences.filter { it.targetRef == elementId }
        val outgoingEdges = definition.sequences.filter { it.sourceRef == elementId }
        if (incomingEdges.size < 2 || outgoingEdges.size < 2) return emptyList()

        val joinId = TopologyIds.fresh("Gateway_join", definition)
        val joinEdgeId = TopologyIds.fresh("Flow_det", definition)
        // Mixed gateways being split typically share their kind with the desired join: an
        // exclusive fork+join was misencoded as one node, and likewise for parallel. Preserve
        // the original kind so the synthesized join doesn't silently change the semantics.
        val joinGw =
            when (gateway) {
                is BpmnParallelGateway -> BpmnParallelGateway(id = joinId, name = null)
                is BpmnInclusiveGateway -> BpmnInclusiveGateway(id = joinId, name = null)
                else -> BpmnExclusiveGateway(id = joinId, name = null)
            }
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
