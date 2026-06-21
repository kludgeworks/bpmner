/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

/**
 * Metadata-only LLM rule wrapper. It is intentionally excluded from [RuleRegistry.activeRules]
 * so the deterministic rule engine never evaluates it, but it implements [BpmnRule] so callers
 * can resolve it through [RuleRegistry.ruleById] and [RuleRegistry.ruleByIdOrAlias] for
 * markdown and diagnostic rendering.
 */
data class LlmRuleSpec(
    override val metadata: RuleMetadata,
) : BpmnRule {
    override val id: String = metadata.id

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
}
