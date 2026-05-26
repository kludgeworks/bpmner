/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import org.springframework.stereotype.Component

/**
 * Removes every sequence flow whose `targetRef` is the given element. Mirrors `deleteIncomingFlows`
 * in `linter/src/auto-fix/registry.ts` — the rule fires on start events that incorrectly have
 * incoming flows; the repair deletes those flows outright. No-op when the element has none.
 */
@Component
internal class DeleteIncomingFlowsHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "deleteIncomingFlows"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val incomingEdges = definition.sequences.filter { it.targetRef == elementId }
        if (incomingEdges.isEmpty()) return emptyList()
        return incomingEdges.map { edge ->
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_EDGE, edgeId = edge.id)
        }
    }
}
