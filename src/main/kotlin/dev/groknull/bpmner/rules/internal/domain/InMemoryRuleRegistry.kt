/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.rules.RuleRegistry
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

/**
 * Default [RuleRegistry] backed by the `List<BpmnRule>` Spring discovers in the
 * application context. When no `BpmnRule` beans are registered (true until #213 lands
 * compiled rules), Spring injects an empty list and the registry reports no active rules.
 *
 * Duplicate-id rules collapse to the last in [rules] under [ruleById] — by design. The
 * Phase 1H startup verification (#216) detects duplicate ids at registry-load time so
 * this lookup stays a pure data structure.
 */
@Service
@Component
internal class InMemoryRuleRegistry(
    rules: List<BpmnRule>,
) : RuleRegistry {
    // Defensive copy: callers that pass a mutable list cannot mutate the registry's
    // active set after construction. Spring's standard injection passes an immutable
    // list, but the constructor contract should not depend on that detail.
    private val rules: List<BpmnRule> = rules.toList()
    private val byId: Map<String, BpmnRule> = this.rules.associateBy { it.id }

    override fun activeRules(): List<BpmnRule> = rules

    override fun ruleById(id: String): BpmnRule? = byId[id]
}
