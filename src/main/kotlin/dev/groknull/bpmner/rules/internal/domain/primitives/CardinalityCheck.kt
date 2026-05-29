/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class CardinalityCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: CardinalityCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: CardinalityCheckConfig,
    ): List<RuleDiagnostic> {
        // Skip `min` rules targeting types the production model can't produce (e.g.
        // `bpmn:InclusiveGateway`). Otherwise a `min` constraint would fire on every
        // evaluation since the count is always zero for unsupported types.
        //
        // The guard is asymmetric: only `min` can false-positive on unmodeled types.
        // For `max`-only rules, count = 0 satisfies any `count <= max ≥ 0`, so the guard
        // is over-cautious. This lets `NoDuplicateDiagrams` (#282) — a `max = 1` rule on
        // `bpmndi:BPMNDiagram` (metadata, not a `BpmnNode` so not in `supportedTypeNames`) —
        // run via the synthetic-element injection from `PrimitiveModelMapping`.
        if (config.min != null && !BpmnTypeMatcher.isSupportedProductionType(config.element)) return emptyList()
        val count = model.elements.count { BpmnTypeMatcher.matches(it.typeName, config.element) }
        val tooFew = config.min?.let { count < it } ?: false
        val tooMany = config.max?.let { count > it } ?: false
        return if (tooFew || tooMany) {
            listOf(metadata.diagnostic(null, "${config.element} count=$count"))
        } else {
            emptyList()
        }
    }
}
