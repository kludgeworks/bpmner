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
 * Expands abbreviations in a node's name using the rule's Pkl `repair.replacementMap`. Mirrors
 * `expandAbbreviations` in `linter/src/auto-fix/registry.ts`: each map key is matched as a
 * whole word (`\b`) and replaced with its expansion.
 *
 * No-op when: node missing, name blank, map missing/empty, or no abbreviation matches.
 */
@Component
internal class ExpandAbbreviationsHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "expandAbbreviations"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        val raw = node.name?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val map = config.replacementMap.orEmpty()
        if (map.isEmpty()) return emptyList()

        val fixed =
            map.entries.fold(raw) { acc, (abbr, expansion) ->
                acc.replace(Regex("\\b${Regex.escape(abbr)}\\b"), expansion)
            }
        if (fixed == raw) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = fixed))
    }
}
