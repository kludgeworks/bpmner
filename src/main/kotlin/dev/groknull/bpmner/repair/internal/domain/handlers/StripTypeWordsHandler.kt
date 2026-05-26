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
 * Removes discouraged type-words ("activity", "process", "event", ...) from a node's name. Mirrors
 * `stripTypeWords` in `linter/src/auto-fix/registry.ts`. The word list is supplied by the rule's
 * Pkl `staticConfig.discouragedWords` and reaches this handler through [HandlerConfig.staticConfig].
 *
 * No-op (returns empty ops) when: node is missing, current name is blank, no `discouragedWords` is
 * configured, the regex would produce an unchanged string, or stripping would leave an empty name.
 */
@Component
internal class StripTypeWordsHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "stripTypeWords"

    @Suppress("ReturnCount") // Guard clauses keep each no-op path locally obvious.
    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        val raw = node.name?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()

        val words = discouragedWords(config) ?: return emptyList()
        if (words.isEmpty()) return emptyList()
        val pattern = wordRemovalPattern(words)

        val fixed =
            raw
                .replace(pattern, "")
                .replace(MULTI_SPACE, " ")
                .trim()
        if (fixed == raw || fixed.isEmpty()) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = fixed))
    }

    private fun discouragedWords(config: HandlerConfig): List<String>? {
        val raw = config.staticConfig?.get(DISCOURAGED_WORDS_KEY) ?: return null
        // Cannot use `raw as? List<String>` directly — JVM erasure makes the generic check
        // unenforceable, so a list containing non-strings would pass the cast and then blow up
        // on iteration. Walk the iterable and keep only the String elements.
        return (raw as? Iterable<*>)?.mapNotNull { it as? String }
    }

    private fun wordRemovalPattern(words: List<String>): Regex {
        val alternation = words.joinToString("|") { Regex.escape(it) }
        return Regex("\\b($alternation)\\b", RegexOption.IGNORE_CASE)
    }

    private companion object {
        const val DISCOURAGED_WORDS_KEY = "discouragedWords"
        val MULTI_SPACE = Regex("\\s{2,}")
    }
}
