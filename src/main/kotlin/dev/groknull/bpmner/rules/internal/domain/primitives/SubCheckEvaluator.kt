/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

/**
 * Routes a typed [DeterministicCheckConfig] to its matching primitive. Used by
 * [CompositeCheck] to dispatch each declared sub-check without each composite carrying its
 * own 10-arm `when`.
 *
 * The parameter is the sealed [DeterministicCheckConfig] so the `when` below is *exhaustive*
 * — the Kotlin compiler enforces that every concrete `DeterministicCheckConfig` subtype is
 * handled, and at the same time rejects any attempt to pass `CompositeCheckConfig` (which
 * isn't a sub-type) or `LlmCheckRuleConfig` (also not). What used to be a runtime
 * `rule-config-error` for those two cases is now caught at compile time.
 *
 * Pure object: no DI, no Spring, no Embabel. The 10 deterministic primitives are
 * instantiated fresh per call — they're stateless data carriers themselves, so the cost
 * is a single object allocation per dispatch.
 */
internal object SubCheckEvaluator {
    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: DeterministicCheckConfig,
    ): List<RuleDiagnostic> = when (config) {
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
    }
}
