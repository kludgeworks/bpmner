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
 * Checks the part-of-speech of the leading token of a property against a target class.
 *
 * Two modes:
 *  - `LEADING_MUST_BE`: fires when the leading token's POS is not [PartOfSpeechCheckConfig.posClass]
 *    (e.g. `VerbObjectName.missingVerb` expects `VERB`).
 *  - `LEADING_MUST_NOT_BE`: fires when the leading token's POS *is* [PartOfSpeechCheckConfig.posClass]
 *    (e.g. `IntermediateEventNotAction` forbids `VERB`).
 *
 * Blank properties don't fire — they're handled by [RequiredPropertyCheck] in a separate
 * sub-check or rule. Tokens classified as [PosTag.OTHER] never satisfy `LEADING_MUST_BE` (so
 * unknown words still trigger the diagnostic) but also never trigger `LEADING_MUST_NOT_BE`
 * (so unknown words don't spuriously fire).
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
            when (config.mode) {
                PartOfSpeechMode.LEADING_MUST_BE ->
                    leadingTag != config.posClass.toNlp()

                PartOfSpeechMode.LEADING_MUST_NOT_BE ->
                    leadingTag == config.posClass.toNlp() && leadingTag != PosTag.OTHER
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
