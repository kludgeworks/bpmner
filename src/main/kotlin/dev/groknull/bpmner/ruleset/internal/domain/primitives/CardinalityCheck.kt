/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

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
        // `bpmn:InclusiveGateway`); a `min` constraint on a never-produced type would fire
        // on every evaluation. The guard is asymmetric: only `min` can false-positive on
        // unmodeled types — for a `max`-only rule, count = 0 trivially satisfies `count <= max ≥ 0`,
        // and types whose elements are injected synthetically (e.g. `bpmndi:BPMNDiagram` from
        // `PrimitiveModelMapping`) need the `max`-only path to reach `model.elements`.
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
