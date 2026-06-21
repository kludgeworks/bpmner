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

/**
 * Rewrites a node's name into sentence case: first non-whitespace token's first character is
 * uppercased; subsequent tokens are lowercased *unless* they look like an acronym.
 *
 * Diverges from the legacy `fixSentenceCase` in `linter/src/auto-fix/registry.ts` in two
 * deliberate ways (PR #247 review):
 *  - **Inner whitespace is preserved.** The TS handler did `split(/\s+/).join(" ")` which
 *    collapses runs of spaces/tabs to a single ASCII space — silently mutating names like
 *    `"Approve  customer order"` whose casing is already correct. The Kotlin port substitutes
 *    each token in place via `Regex("\\S+").replace`, leaving whitespace alone.
 *  - **Acronym recognition tolerates adjacent punctuation.** `ACRONYM` now accepts non-alphabetic
 *    characters either side of the 2+ uppercase run, so `"API,"`, `"BPMN."`, and `"(JSON)"` are
 *    preserved instead of getting lowercased to `"aPI,"`.
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
        var seenFirst = false
        return TOKEN.replace(raw) { match ->
            val word = match.value
            val transformed = when {
                !seenFirst -> word.replaceFirstChar { it.uppercaseChar() }
                ACRONYM.matches(word) -> word
                else -> word.replaceFirstChar { it.lowercaseChar() }
            }
            seenFirst = true
            transformed
        }
    }

    private companion object {
        val TOKEN = Regex("\\S+")

        // "Acronym" = 2+ consecutive uppercase letters with no lowercase anywhere in the token.
        // Leading / trailing non-alphabetic chars (digits, punctuation) are allowed so names like
        // `"API,"` or `"(BPMN)"` are recognised.
        val ACRONYM = Regex("^[^a-z]*[A-Z]{2,}[^a-z]*$")
    }
}
