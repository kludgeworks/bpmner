/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.LlmCheckRuleConfig

/**
 * Routes a typed [Any] config to its matching deterministic primitive. Used by
 * [CompositeCheck] to dispatch each declared sub-check to the right primitive without each
 * composite carrying its own 10-arm `when`.
 *
 * Pure object: no DI, no Spring, no Embabel. The 10 deterministic primitives are
 * instantiated fresh per call — they're stateless data carriers themselves, so the cost
 * is a single object allocation per dispatch.
 *
 * `LlmCheckRuleConfig` and nested `CompositeCheckConfig` are explicitly rejected: the LLM
 * tier lives in a separate `@Agent` (different evaluation model), and nesting composites
 * would make diagnostic-code attribution ambiguous. The Pkl schema's `SubCheck` doc
 * comment forbids both at authoring time; this dispatcher catches any drift at runtime.
 */
internal object SubCheckEvaluator {
    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: Any,
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

        is CompositeCheckConfig ->
            listOf(metadata.configError("CompositeCheck cannot nest inside another CompositeCheck"))

        is LlmCheckRuleConfig ->
            listOf(metadata.configError("LlmCheckRule sub-checks belong to LlmRuleAgent, not CompositeCheck"))

        else -> listOf(metadata.configError("Unknown sub-check config type: ${config::class.qualifiedName}"))
    }
}
