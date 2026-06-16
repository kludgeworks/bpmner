/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

/**
 * Metadata-only LLM rule wrapper. It is intentionally excluded from [RuleRegistry.activeRules]
 * so the deterministic rule engine never evaluates it, but it implements [BpmnRule] so callers
 * can resolve it through [RuleRegistry.ruleById] and [RuleRegistry.ruleByIdOrAlias] for
 * markdown and diagnostic rendering.
 */
data class LlmRuleSpec(
    override val metadata: RuleMetadata,
    val staticConfig: Map<String, Any> = emptyMap(),
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
}
