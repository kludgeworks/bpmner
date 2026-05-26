/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.rules.RuleRegistry

/**
 * Plain `RuleRegistry` backed by a fixed `List<BpmnRule>`. Not a Spring `@Component` —
 * [PklRuleCatalog] is the single registered registry in production. This class is kept
 * around as a test utility (e.g. `BpmnerParityTest`) and for any caller that wants an
 * in-process registry without booting the full Pkl pipeline.
 *
 * Duplicate-id rules collapse to the last entry under [ruleById]; the Phase 1H startup
 * verification (#216) is what guards against duplicate ids reaching production.
 */
internal class InMemoryRuleRegistry(
    rules: List<BpmnRule>,
) : RuleRegistry {
    private val rules: List<BpmnRule> = rules.toList()
    private val byId: Map<String, BpmnRule> = this.rules.associateBy { it.id }

    override fun activeRules(): List<BpmnRule> = rules

    override fun ruleById(id: String): BpmnRule? = byId[id]
}
