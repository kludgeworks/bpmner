/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class PropertyPatternCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PropertyPatternCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PropertyPatternCheckConfig,
    ): List<RuleDiagnostic> {
        val regex = Regex(config.pattern)
        return metadata.targetedElements(model)
            .filter { element ->
                val value = element.property(config.property)
                !value.isNullOrBlank() && !regex.matches(value)
            }
            .map { metadata.diagnostic(it.id, config.patternDescription ?: config.pattern) }
    }
}
