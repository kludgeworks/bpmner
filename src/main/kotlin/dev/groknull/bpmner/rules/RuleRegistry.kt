/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.BpmnRule
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Holds the [BpmnRule] instances the [RuleEngine] iterates over for each evaluation.
 *
 * Today populated entirely by Spring DI of `@Component class … : BpmnRule` beans — see
 * [dev.groknull.bpmner.rules.internal.domain.InMemoryRuleRegistry]. Tier-2 (Pkl-authored
 * rules) and Tier-3 (plugin JARs) loaders will plug into the registry later via additional
 * implementations or composition.
 *
 * Rule ids are expected to be unique across the active set; duplicate ids collapse in the
 * `byId` lookup (last-wins). Detection of duplicates is deferred to the Phase 1H startup
 * check (#216) so the registry stays a pure lookup data structure.
 */
@PrimaryPort
interface RuleRegistry {
    /** Every rule currently active. Empty when no `BpmnRule` beans are registered. */
    fun activeRules(): List<BpmnRule>

    /** The rule with the given [BpmnRule.id], or `null` if no such rule is registered. */
    fun ruleById(id: String): BpmnRule?
}
