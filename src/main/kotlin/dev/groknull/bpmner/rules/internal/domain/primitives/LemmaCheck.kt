/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp

/**
 * Lemma-aware vocabulary check. Lemmatises the tokens of a property and matches against a
 * configured set of base-form words.
 *
 * Useful for rules that want to recognise inflected forms equivalently — e.g. a question-
 * starter check that wants `is`, `are`, `was` to all match the lemma `be`.
 *
 * Four modes mirror [VocabularyCheck]'s scheme but operate on lemmatised tokens:
 *  - `REQUIRE_LEADING_LEMMA`: fires when the leading token's lemma is NOT in [LemmaCheckConfig.lemmas].
 *  - `FORBID_LEADING_LEMMA`: fires when the leading token's lemma IS in [LemmaCheckConfig.lemmas].
 *  - `REQUIRE_ANY_LEMMA`: fires when no token's lemma matches.
 *  - `FORBID_ANY_LEMMA`: fires when any token's lemma matches.
 *
 * Blank properties don't fire. Lemmas in the config are case-folded once at evaluation
 * time so authors can write them in any case.
 */
internal class LemmaCheck(
    private val nlp: BpmnNlp,
) {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: LemmaCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: LemmaCheckConfig,
    ): List<RuleDiagnostic> {
        val needle = config.lemmas.map { it.lowercase() }.toSet()
        return metadata.targetedElements(model)
            .filter { element ->
                val value = element.property(config.property)
                if (value.isNullOrBlank()) return@filter false
                val lemmas = nlp.lemmasOf(value).map { it.lowercase() }
                if (lemmas.isEmpty()) return@filter false
                when (config.mode) {
                    LemmaMode.REQUIRE_LEADING_LEMMA -> lemmas.first() !in needle
                    LemmaMode.FORBID_LEADING_LEMMA -> lemmas.first() in needle
                    LemmaMode.REQUIRE_ANY_LEMMA -> lemmas.none { it in needle }
                    LemmaMode.FORBID_ANY_LEMMA -> lemmas.any { it in needle }
                }
            }
            .map { metadata.diagnostic(it.id, config.lemmas.joinToString(", ")) }
    }
}
