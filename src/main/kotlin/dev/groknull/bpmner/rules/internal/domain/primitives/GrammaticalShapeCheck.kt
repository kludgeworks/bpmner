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
 *  - `STATE_LABEL`: a state or happening — leading token is a noun OR a past-participle
 *    verbal (`Order received`, `Payment failed`, `Approved`). Fires when the leading token
 *    is an action-shaped verb in any other form (`Process the order`, `Send invoice`).
 *  - `ACTION_LABEL`: an action — leading token is a verb (any form except past-participle
 *    when the rest of the label is empty). Fires when the leading token is a noun. Used
 *    by activity-name rules.
 *  - `QUESTION_FORM`: a question — leading token is a WH-word or modal/copular auxiliary,
 *    OR the label ends with `?`. Fires otherwise. Used by `DivergingGatewayQuestion`.
 *
 * Blank properties don't fire. Single-word labels are interpreted with the rules above —
 * `"Approved"` passes `STATE_LABEL` because its leading (only) token is a past-participle
 * verb form (`-ed` suffix); `"Approve"` fails because it's a bare infinitive.
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
        val tags = nlp.posTags(text)
        val leadingTag = tags.first()
        val leading = tokens.first()
        return when (shape) {
            GrammaticalShape.STATE_LABEL ->
                leadingTag == PosTag.NOUN ||
                    (leadingTag == PosTag.VERB && leading.lowercase().endsWith("ed")) ||
                    leadingTag == PosTag.ADJ ||
                    leadingTag == PosTag.OTHER

            GrammaticalShape.ACTION_LABEL ->
                leadingTag == PosTag.VERB && !leading.lowercase().endsWith("ed")

            GrammaticalShape.QUESTION_FORM ->
                leadingTag == PosTag.WH || leadingTag == PosTag.AUX || text.trimEnd().endsWith("?")
        }
    }
}
