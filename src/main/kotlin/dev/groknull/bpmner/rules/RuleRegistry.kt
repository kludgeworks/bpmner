/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.bpmn.BpmnRule
import org.jmolecules.architecture.hexagonal.PrimaryPort

/**
 * Holds the [BpmnRule] instances the [RuleEngine] iterates over for each evaluation, plus
 * metadata-only LLM rule specs used for guidance generation.
 *
 * Today populated entirely by Spring DI of `@Component class … : BpmnRule` beans plus
 * `@Bean LlmRuleSpec` beans — see [dev.groknull.bpmner.rules.internal.domain.InMemoryRuleRegistry]
 * and [dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry].
 *
 * Rule ids are expected to be unique across the active set; duplicate ids collapse in the
 * `byId` lookup (last-wins). Detection of duplicates is handled by the registry implementation
 * via `require(...)` checks at construction time (#380).
 */
@PrimaryPort
interface RuleRegistry {
    /** Every rule currently active (executable). Empty when no `BpmnRule` beans are registered. */
    fun activeRules(): List<BpmnRule>

    /** The rule with the given [BpmnRule.id], or `null` if no such rule is registered. */
    fun ruleById(id: String): BpmnRule?

    /**
     * Lookup by [BpmnRule.id] OR by any entry in [dev.groknull.bpmner.api.RuleMetadata.aliases].
     * Used by consumers (notably the diagnostic→repair mapping) that may receive a legacy
     * alias-form id such as `gen-02-no-duplicate-diagrams` and need to resolve back to the
     * canonical rule.
     *
     * **The default implementation only resolves canonical ids** — it delegates to
     * [ruleById] and has no alias index. Implementations that want to honour
     * [RuleMetadata.aliases] must override this method and build their own alias map.
     * Test stubs that don't override will silently return `null` for any alias-form id.
     */
    fun ruleByIdOrAlias(id: String): BpmnRule? = ruleById(id)

    /**
     * Returns metadata-only LLM rule specs (not executable). These are used for guidance text
     * generation and markdown rendering but are excluded from [activeRules()] because they
     * do not execute against the BPMN rule engine.
     *
     * Today populated by Spring DI of `@Bean LlmRuleSpec` beans — see
     * [dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry].
     */
    fun llmRuleSpecs(): List<LlmRuleSpec> = emptyList()
}
