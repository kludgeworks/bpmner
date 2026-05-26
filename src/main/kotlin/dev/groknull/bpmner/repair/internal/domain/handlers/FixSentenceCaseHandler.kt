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
 * Rewrites a node's name into sentence case. Mirrors `fixSentenceCase` in
 * `linter/src/auto-fix/registry.ts`: first word's first character uppercased; subsequent words
 * lowercased *unless* they are 2+ uppercase letters (acronym preservation).
 */
@Component
internal class FixSentenceCaseHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "fixSentenceCase"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        val raw = node.name?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        val fixed = toSentenceCase(raw)
        if (fixed == raw) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = fixed))
    }

    private fun toSentenceCase(raw: String): String {
        val words = raw.split(WHITESPACE)
        return words
            .mapIndexed { idx, word ->
                when {
                    word.isEmpty() -> word
                    idx == 0 -> word.replaceFirstChar { it.uppercaseChar() }
                    ACRONYM.matches(word) -> word
                    else -> word.replaceFirstChar { it.lowercaseChar() }
                }
            }.joinToString(" ")
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val ACRONYM = Regex("^[A-Z]{2,}$")
    }
}
