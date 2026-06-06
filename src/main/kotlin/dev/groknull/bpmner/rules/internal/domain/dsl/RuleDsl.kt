/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.dsl

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.LlmCheckRuleConfig
import dev.groknull.bpmner.rules.LlmRuleSpec
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckConfig

/*
 * Builder functions for authoring BPMN rules directly in Kotlin (#377). A rule is a data literal:
 * the builder derives `slug`/`id`, packs the rest into [RuleMetadata], and wraps it in the right
 * [BpmnRule] shape (see `Rules.kt`). The 49 Pkl rules migrate onto these in #378.
 */

// Mirrors `linter/pkl/schema/BpmnRule.pkl` exactly:
//   slug = name.toLowerCase().replaceAll(Regex("[^a-z0-9]+"), "-")   // NOTE: no leading/trailing trim
//   id   = "\(category.shortCode)-\(slug)"
private val SLUG_NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

/** Rule slug: lowercased name with each run of non-`[a-z0-9]` collapsed to a single `-`. No trim. */
internal fun ruleSlug(name: String): String = name.lowercase().replace(SLUG_NON_ALPHANUMERIC, "-")

/** Rule id: `"<category.shortCode>-<slug>"`. The single derivation point — keep it byte-faithful. */
internal fun ruleId(category: RuleCategory, name: String): String = "${category.shortCode}-${ruleSlug(name)}"

@Suppress("LongParameterList") // A rule is a flat record of metadata fields; named args at call sites.
private fun ruleMetadata(
    name: String,
    category: RuleCategory,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    severity: RuleSeverity,
    repair: RepairMetadata,
    aliases: List<String>,
    deprecated: Boolean,
    replacedBy: List<String>,
    deprecationReason: String?,
): RuleMetadata = RuleMetadata(
    id = ruleId(category, name),
    name = name,
    slug = ruleSlug(name),
    category = category,
    intent = intent,
    forModellers = forModellers,
    forAI = forAI,
    targetElements = targetElements,
    errorMessages = errorMessages,
    severity = severity,
    repair = repair,
    aliases = aliases,
    deprecated = deprecated,
    replacedBy = replacedBy,
    deprecationReason = deprecationReason,
)

/** A deterministic single-primitive rule. */
@Suppress("LongParameterList")
internal fun primitiveRule(
    name: String,
    category: RuleCategory,
    config: DeterministicCheckConfig,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    nlp: BpmnNlp,
    severity: RuleSeverity = RuleSeverity.WARNING,
    repair: RepairMetadata = RepairMetadata(),
    aliases: List<String> = emptyList(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
): BpmnRule = DeterministicRule(
    ruleMetadata(
        name, category, intent, forModellers, forAI, targetElements, errorMessages,
        severity, repair, aliases, deprecated, replacedBy, deprecationReason,
    ),
    config,
    nlp,
)

/** A composite rule: build its named deterministic sub-checks inside the [build] block. */
@Suppress("LongParameterList")
internal fun compositeRule(
    name: String,
    category: RuleCategory,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    nlp: BpmnNlp,
    severity: RuleSeverity = RuleSeverity.WARNING,
    repair: RepairMetadata = RepairMetadata(),
    aliases: List<String> = emptyList(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
    build: CompositeBuilder.() -> Unit,
): BpmnRule = CompositeRule(
    ruleMetadata(
        name, category, intent, forModellers, forAI, targetElements, errorMessages,
        severity, repair, aliases, deprecated, replacedBy, deprecationReason,
    ),
    CompositeBuilder(targetElements).apply(build).build(),
    nlp,
)

/**
 * An LLM-judged rule. Returns an [LlmRuleSpec] (not a [BpmnRule]) — LLM rules are evaluated by
 * `LlmRuleAgent`, not the deterministic engine. `severity` defaults to [RuleSeverity.INFO].
 */
@Suppress("LongParameterList")
internal fun llmRule(
    name: String,
    category: RuleCategory,
    prompt: String,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    rubric: String? = null,
    severity: RuleSeverity = RuleSeverity.INFO,
    aliases: List<String> = emptyList(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
): LlmRuleSpec = LlmRuleSpec(
    metadata = ruleMetadata(
        name, category, intent, forModellers, forAI, targetElements, errorMessages,
        severity, RepairMetadata(), aliases, deprecated, replacedBy, deprecationReason,
    ),
    config = LlmCheckRuleConfig(prompt = prompt, rubric = rubric),
)

/** Collects the named deterministic sub-checks of a [compositeRule]. */
internal class CompositeBuilder(private val targetTypes: List<String>) {
    private val subChecks = mutableListOf<SubCheckConfig>()

    /** Add a sub-check keyed by [diagnosticCode] (the key into the rule's `errorMessages`). */
    fun sub(diagnosticCode: String, config: DeterministicCheckConfig) {
        subChecks += SubCheckConfig(diagnosticCode = diagnosticCode, config = config)
    }

    internal fun build(): CompositeCheckConfig = CompositeCheckConfig(targetTypes = targetTypes, subChecks = subChecks.toList())
}
