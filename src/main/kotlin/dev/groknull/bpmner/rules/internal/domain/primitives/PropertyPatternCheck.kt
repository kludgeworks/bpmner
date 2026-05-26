/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import java.util.regex.PatternSyntaxException

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
        // Compile the pattern eagerly so a malformed regex from a rule's Pkl `pattern` field
        // surfaces as a focused rule-config-error diagnostic instead of an opaque
        // `rule-execution-failure` from the engine's outer runCatching wrapper.
        val regex = try {
            Regex(config.pattern)
        } catch (e: PatternSyntaxException) {
            return listOf(metadata.configError("pattern '${config.pattern}' is not a valid regex: ${e.description}"))
        }
        return metadata.targetedElements(model)
            .filter { element ->
                val value = element.property(config.property)
                !value.isNullOrBlank() && !regex.matches(value)
            }
            .map { metadata.diagnostic(it.id, config.patternDescription ?: config.pattern) }
    }
}
