/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.nlp.PosTag

/**
 * Higher-level grammatical-shape detector layered over the [BpmnNlp] POS surface.
 *
 * Encodes BPMN-modelling conventions that are more expressive than a single-POS check:
 *
 *  - `STATE_LABEL`: a state or happening — leading token is a noun, past tense/past-
 *    participle verb form, or adjective (`Order received`, `Payment failed`, `Approved`,
 *    `Customer details`). Fires when the leading token is an active verb form (`Process
 *    the order`, `Send invoice`).
 *  - `ACTION_LABEL`: an action — leading token is an active verb form. Fires when the
 *    leading token is anything else (noun, past-participle, adjective, …). Used by
 *    activity-name rules.
 *  - `QUESTION_FORM`: a question — leading token is a WH-word or modal/copular auxiliary,
 *    OR the label ends with `?`. Fires otherwise. Used by `DivergingGatewayQuestion`.
 *
 * Blank properties don't fire. The state/action split delegates "past-tense recognition"
 * to [BpmnNlp]'s [PosTag.VERB_STATE], which uses the dictionary-derived Penn tag rather
 * than a fragile `endsWith("ed")` heuristic — so irregulars like `sent`, `made`, `broken`
 * pass `STATE_LABEL` correctly.
 *
 * [PosTag.OTHER] (unknown / out-of-vocabulary) passes every shape conservatively — the
 * check is meant to flag clear violations, not penalise unknown vocabulary.
 */
internal class GrammaticalShapeCheck(
    private val nlp: BpmnNlp,
) {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: GrammaticalShapeCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: GrammaticalShapeCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { element ->
            val value = element.property(config.property)
            if (value.isNullOrBlank()) return@filter false
            !matchesShape(value, config.mode)
        }
        .map { metadata.diagnostic(it.id, config.property) }

    private fun matchesShape(text: String, shape: GrammaticalShape): Boolean {
        val tokens = nlp.tokens(text)
        if (tokens.isEmpty()) return true // pure-punctuation labels skip silently
        val leadingTag = nlp.posTags(text).first()
        // OTHER passes every shape conservatively — see class-level KDoc. The check is meant
        // to flag clear violations, so an unknown leading token never fires a diagnostic.
        if (leadingTag == PosTag.OTHER) return true
        return when (shape) {
            GrammaticalShape.STATE_LABEL -> leadingTag in STATE_LIKE_TAGS

            GrammaticalShape.ACTION_LABEL -> leadingTag == PosTag.VERB

            GrammaticalShape.QUESTION_FORM ->
                leadingTag == PosTag.WH || leadingTag == PosTag.AUX || text.trimEnd().endsWith("?")
        }
    }

    private companion object {
        /** Tags that read as states/outcomes/objects in BPMN labels — no action implied. */
        private val STATE_LIKE_TAGS = setOf(PosTag.NOUN, PosTag.VERB_STATE, PosTag.ADJ)
    }
}
