/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import org.springframework.stereotype.Component

@Component
internal class ConvergingGatewayClearNameHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "clearConvergingGatewayName"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        if (node.name.isNullOrBlank()) return emptyList()
        val incoming = definition.sequences.count { it.targetRef == elementId }
        val outgoing = definition.sequences.count { it.sourceRef == elementId }
        // Converging-only: two or more incoming flows and at most one outgoing flow.
        if (incoming <= 1 || outgoing > 1) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = null))
    }
}
