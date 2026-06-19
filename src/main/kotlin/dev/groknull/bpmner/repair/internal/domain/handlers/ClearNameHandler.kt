/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import org.springframework.stereotype.Component

/**
 * Clears a node's name. Mirrors `clearName` in `linter/src/auto-fix/registry.ts`.
 *
 * Idempotent: returns no ops when the node is missing or already has a blank name.
 */
@Component
internal class ClearNameHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "clearName"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        if (node.name.isNullOrBlank()) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = null))
    }
}
