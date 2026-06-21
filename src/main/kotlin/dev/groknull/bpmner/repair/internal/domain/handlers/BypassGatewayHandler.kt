/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import org.springframework.stereotype.Component

@Component
internal class BypassGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "bypassGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val incomingEdge = definition.sequences.singleOrNull { it.targetRef == elementId } ?: return emptyList()
        val outgoingEdge = definition.sequences.singleOrNull { it.sourceRef == elementId } ?: return emptyList()
        val updatedIncoming = incomingEdge.copy(targetRef = outgoingEdge.targetRef)
        return listOf(
            BpmnPatchOperation(
                type = BpmnPatchOperationType.REPLACE_EDGE,
                edgeId = incomingEdge.id,
                edge = updatedIncoming,
            ),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_EDGE, edgeId = outgoingEdge.id),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_NODE, nodeId = elementId),
        )
    }
}
