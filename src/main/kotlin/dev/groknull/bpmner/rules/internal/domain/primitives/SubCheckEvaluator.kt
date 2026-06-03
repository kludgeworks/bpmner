/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp

/**
 * Routes a typed [DeterministicCheckConfig] to its matching primitive. Used by
 * [CompositeCheck] to dispatch each declared sub-check without each composite carrying its
 * own `when`.
 *
 * The parameter is the sealed [DeterministicCheckConfig] so the `when` below is *exhaustive*
 * — the Kotlin compiler enforces that every concrete `DeterministicCheckConfig` subtype is
 * handled, and at the same time rejects any attempt to pass `CompositeCheckConfig` (which
 * isn't a sub-type) or `LlmCheckRuleConfig` (also not). What used to be a runtime
 * `rule-config-error` for those two cases is now caught at compile time.
 *
 * The [nlp] parameter is the application-wide [BpmnNlp] facade — threaded here from
 * [dev.groknull.bpmner.rules.internal.domain.PklRuleCatalog] so the NLP-aware primitives
 * (Phase 3, #218) have access without each one being Spring-managed individually. Non-NLP
 * primitives ignore it.
 *
 * Pure object: no DI, no Spring, no Embabel. Primitives are instantiated fresh per call —
 * they're stateless data carriers, so the cost is a single object allocation per dispatch.
 */
internal object SubCheckEvaluator {
    @Suppress("CyclomaticComplexMethod")
    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: DeterministicCheckConfig,
        nlp: BpmnNlp,
    ): List<RuleDiagnostic> = when (config) {
        is PresenceCheckConfig -> PresenceCheck().evaluate(model, metadata, config)
        is RequiredPropertyCheckConfig -> RequiredPropertyCheck().evaluate(model, metadata, config)
        is PropertyPatternCheckConfig -> PropertyPatternCheck().evaluate(model, metadata, config)
        is VocabularyCheckConfig -> VocabularyCheck().evaluate(model, metadata, config)
        is RequiredAssociationCheckConfig -> RequiredAssociationCheck().evaluate(model, metadata, config)
        is TopologyCheckConfig -> TopologyCheck().evaluate(model, metadata, config)
        is ConnectivityCheckConfig -> ConnectivityCheck().evaluate(model, metadata, config)
        is PairingCheckConfig -> PairingCheck().evaluate(model, metadata, config)
        is CardinalityCheckConfig -> CardinalityCheck().evaluate(model, metadata, config)
        is PoolLabelCheckConfig -> PoolLabelCheck().evaluate(model, metadata, config)
        is ElementConstraintCheckConfig -> ElementConstraintCheck().evaluate(model, metadata, config)
        is PartOfSpeechCheckConfig -> PartOfSpeechCheck(nlp).evaluate(model, metadata, config)
        is LemmaCheckConfig -> LemmaCheck(nlp).evaluate(model, metadata, config)
        is GrammaticalShapeCheckConfig -> GrammaticalShapeCheck(nlp).evaluate(model, metadata, config)
    }
}
