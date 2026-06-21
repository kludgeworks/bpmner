/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.isTask
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import org.springframework.stereotype.Component

@Component
internal class InsertConvergingGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "insertConvergingGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val task = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        if (!task.isTask()) return emptyList()
        val incomingEdges = definition.sequences.filter { it.targetRef == elementId }
        if (incomingEdges.size < 2) return emptyList()

        val joinId = TopologyIds.fresh("Gateway_join", definition)
        val joinEdgeId = TopologyIds.fresh("Flow_det", definition)
        // The kind of synthesized join depends on the upstream topology: if every incoming
        // flow traces back to the same parallel fork, we need a parallel join to preserve
        // "wait for all branches" semantics. Otherwise the historical exclusive merge is
        // correct.
        val joinGw = JoinGatewayKindSelector.newJoinGateway(definition, joinId, incomingEdges)
        val joinToTask =
            BpmnEdge(
                id = joinEdgeId,
                sourceRef = joinId,
                targetRef = task.id,
            )

        val ops = mutableListOf<BpmnPatchOperation>()
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_NODE, node = joinGw)
        ops += BpmnPatchOperation(type = BpmnPatchOperationType.ADD_EDGE, edge = joinToTask)
        for (edge in incomingEdges) {
            val updated = edge.copy(targetRef = joinId)
            ops += BpmnPatchOperation(type = BpmnPatchOperationType.REPLACE_EDGE, edgeId = edge.id, edge = updated)
        }
        return ops
    }
}
