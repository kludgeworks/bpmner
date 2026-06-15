/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.rules.RuleRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Spring-based [RuleRegistry] that collects all `@Bean BpmnRule` beans from category configs
 * (`*RuleConfig.kt`) and the 7 compiled `@Component` rules in `compiled/`.
 *
 * Mirrors [PklRuleCatalog] in public surface: [activeRules()], [ruleById], [ruleByIdOrAlias]
 * with the same id/alias dedup logic.
 *
 * Conditional on `bpmner.rules.source=kotlin`. When that property is not set or is `pkl`,
 * [PklRuleCatalog] remains the active registry. This enables the staged transition from Pkl to
 * Kotlin rule definitions without changing default runtime behavior.
 */
@Component
@ConditionalOnProperty(name = ["bpmner.rules.source"], havingValue = "kotlin")
internal class BeanRuleRegistry(private val rules: List<BpmnRule>) : RuleRegistry {
    private val byId: Map<String, BpmnRule>
    private val byAlias: Map<String, BpmnRule>

    init {
        byId = rules.associateBy { it.id }
        byAlias = rules
            .flatMap { rule -> rule.metadata.aliases.map { alias -> alias to rule } }
            .filter { (alias, _) -> alias !in byId }
            .toMap()
    }

    override fun activeRules(): List<BpmnRule> = rules

    override fun ruleById(id: String): BpmnRule? = byId[id]

    override fun ruleByIdOrAlias(id: String): BpmnRule? = byId[id] ?: byAlias[id]
}
