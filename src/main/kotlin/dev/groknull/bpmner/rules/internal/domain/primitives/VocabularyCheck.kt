/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class VocabularyCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: VocabularyCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: VocabularyCheckConfig,
    ): List<RuleDiagnostic> {
        val words = config.words.map { it.lowercase() }.toSet()
        return metadata.targetedElements(model)
            .filter { element ->
                val tokens = element.property(config.property).tokens()
                val matched = tokens.any { it in words }
                when (config.mode) {
                    VocabularyMode.REQUIRE -> !matched
                    VocabularyMode.FORBID -> matched
                }
            }
            .map { metadata.diagnostic(it.id, config.words.joinToString(", ")) }
    }

    private fun String?.tokens(): Set<String> = orEmpty()
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
        .toSet()
}
