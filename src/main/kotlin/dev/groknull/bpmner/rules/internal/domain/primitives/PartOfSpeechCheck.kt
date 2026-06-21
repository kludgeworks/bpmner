/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.nlp.PosTag

/**
 * Checks the part-of-speech of the leading token of a property against a target class.
 *
 * Two modes:
 *  - `LEADING_MUST_BE`: fires when the leading token's POS is not [PartOfSpeechCheckConfig.posClass]
 *    (e.g. `VerbObjectName.missingVerb` expects `VERB`).
 *  - `LEADING_MUST_NOT_BE`: fires when the leading token's POS *is* [PartOfSpeechCheckConfig.posClass]
 *    (e.g. `IntermediateEventNotAction` forbids `VERB`).
 *
 * Blank properties don't fire — they're handled by [RequiredPropertyCheck] in a separate
 * sub-check or rule.
 *
 * **`OTHER` is symmetrically conservative**: when the leading token is [PosTag.OTHER]
 * (unknown / out-of-vocabulary), neither mode fires. Both modes share the same guard so
 * the same input never trips both directions. This matches [PosTag.OTHER]'s contract.
 */
internal class PartOfSpeechCheck(
    private val nlp: BpmnNlp,
) {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PartOfSpeechCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PartOfSpeechCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { element ->
            val value = element.property(config.property)
            if (value.isNullOrBlank()) return@filter false
            val leadingTag = nlp.posTags(value).firstOrNull() ?: return@filter false
            if (leadingTag == PosTag.OTHER) return@filter false
            val target = config.posClass.toNlp()
            when (config.mode) {
                PartOfSpeechMode.LEADING_MUST_BE -> leadingTag != target
                PartOfSpeechMode.LEADING_MUST_NOT_BE -> leadingTag == target
            }
        }
        .map { metadata.diagnostic(it.id, config.property) }

    private fun NlpPosTag.toNlp(): PosTag = when (this) {
        NlpPosTag.VERB -> PosTag.VERB
        NlpPosTag.NOUN -> PosTag.NOUN
        NlpPosTag.ADJ -> PosTag.ADJ
        NlpPosTag.AUX -> PosTag.AUX
        NlpPosTag.WH -> PosTag.WH
        NlpPosTag.OTHER -> PosTag.OTHER
    }
}
