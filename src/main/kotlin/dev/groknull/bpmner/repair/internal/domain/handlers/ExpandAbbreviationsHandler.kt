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
 * Expands abbreviations in a node's name using the rule's Pkl `repair.replacementMap`.
 *
 * Diverges from the legacy `expandAbbreviations` in `linter/src/auto-fix/registry.ts` in two
 * deliberate ways (PR #247 review):
 *  - **Single-pass alternation** — all replacements are applied via one combined regex, so the
 *    expansion of abbreviation `A` is never re-scanned for another key `B`. The TS handler
 *    folded per-key, which could chain expansions if a value happened to be another key.
 *  - **Lookaround boundaries** — uses `(?<!\w)` / `(?!\w)` instead of `\b`. This still rejects
 *    sub-string matches inside longer words (`REQ` inside `REQUIRED`) but also matches
 *    abbreviations sitting next to punctuation, including keys that themselves end with a
 *    non-word character (e.g. `"corp."`).
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

        val alternation = map.keys.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("(?<!\\w)($alternation)(?!\\w)")
        val fixed = pattern.replace(raw) { match -> map[match.value] ?: match.value }
        if (fixed == raw) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = fixed))
    }
}
