/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.rules.LlmRuleSpec
import dev.groknull.bpmner.rules.RuleRegistry
import org.springframework.stereotype.Component

/**
 * Spring-based [RuleRegistry] that collects all `@Bean BpmnRule` beans from category configs
 * (`*RuleConfig.kt`) and the 7 compiled `@Component` rules in `compiled/`, plus metadata-only
 * `@Bean LlmRuleSpec` beans.
 *
 * Exposes executable rules via [activeRules()] and resolves both executable rules and
 * metadata-only LLM specs via [ruleById] / [ruleByIdOrAlias].
 *
 * The bean registry is now the default runtime registry (no `@ConditionalOnProperty`). Pkl rule
 * catalog and bridge code are removed after #380 cutover.
 */
@Component
internal class BeanRuleRegistry(
    bpmnRules: List<BpmnRule>,
    private val llmRuleSpecs: List<LlmRuleSpec>,
) : RuleRegistry {
    private val executableRules: List<BpmnRule> = bpmnRules.filterNot { it is LlmRuleSpec }
    private val byId: Map<String, BpmnRule>
    private val byAlias: Map<String, BpmnRule>

    init {
        // Validate unique ids across executable rules
        val executableIds = executableRules.map { it.id }
        val duplicateExecutableIds = executableIds.groupBy { it }.filterValues { it.size > 1 }.keys
        require(executableIds.size == executableIds.distinct().size) {
            "BeanRuleRegistry: duplicate rule ids in executable rules: $duplicateExecutableIds"
        }

        // Validate unique ids across LLM rule specs (they're metadata-only)
        val llmIds = llmRuleSpecs.map { it.metadata.id }
        val duplicateLlmIds = llmIds.groupBy { it }.filterValues { it.size > 1 }.keys
        require(llmIds.size == llmIds.distinct().size) {
            "BeanRuleRegistry: duplicate rule ids in LLM rule specs: $duplicateLlmIds"
        }

        val resolvableRules = executableRules + llmRuleSpecs

        // Build id-to-rule map from executable rules plus metadata-only LLM wrappers.
        byId = resolvableRules.associateBy { it.id }

        // Build alias map from all resolvable rules, excluding aliases that collide with canonical ids.
        byAlias = resolvableRules
            .flatMap { rule -> rule.metadata.aliases.map { alias -> alias to rule } }
            .filter { (alias, _) -> alias !in byId }
            .toMap()
    }

    override fun activeRules(): List<BpmnRule> = executableRules

    override fun ruleById(id: String): BpmnRule? = byId[id]

    override fun ruleByIdOrAlias(id: String): BpmnRule? = byId[id] ?: byAlias[id]

    override fun llmRuleSpecs(): List<LlmRuleSpec> = llmRuleSpecs
}
