/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.ruleset.LlmRuleSpec
import dev.groknull.bpmner.ruleset.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.ruleset.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.SubCheckConfig

// DSL entry points intentionally expose rule metadata fields directly so rule definitions stay explicit.
@Suppress("LongParameterList")
internal fun primitiveRule(
    name: String,
    category: RuleCategory,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    check: DeterministicCheckConfig,
    nlp: BpmnNlp,
    severity: RuleSeverity = RuleSeverity.WARNING,
    aliases: List<String> = emptyList(),
    repair: RepairMetadata = RepairMetadata(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
): BpmnRule = DeterministicRule(
    metadata = ruleMetadata(
        name = name,
        category = category,
        intent = intent,
        forModellers = forModellers,
        forAI = forAI,
        targetElements = targetElements,
        errorMessages = errorMessages,
        severity = severity,
        aliases = aliases,
        repair = repair,
        deprecated = deprecated,
        replacedBy = replacedBy,
        deprecationReason = deprecationReason,
    ),
    config = check,
    nlp = nlp,
)

// DSL entry points intentionally expose rule metadata fields directly so rule definitions stay explicit.
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
    aliases: List<String> = emptyList(),
    repair: RepairMetadata = RepairMetadata(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
    configure: CompositeRuleBuilder.() -> Unit,
): BpmnRule {
    val builder = CompositeRuleBuilder().apply(configure)
    return CompositeRule(
        metadata = ruleMetadata(
            name = name,
            category = category,
            intent = intent,
            forModellers = forModellers,
            forAI = forAI,
            targetElements = targetElements,
            errorMessages = errorMessages,
            severity = severity,
            aliases = aliases,
            repair = repair,
            deprecated = deprecated,
            replacedBy = replacedBy,
            deprecationReason = deprecationReason,
        ),
        config = builder.build(),
        nlp = nlp,
    )
}

// DSL entry points intentionally expose rule metadata fields directly so rule definitions stay explicit.
@Suppress("LongParameterList")
internal fun llmRule(
    name: String,
    category: RuleCategory,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    severity: RuleSeverity = RuleSeverity.WARNING,
    aliases: List<String> = emptyList(),
    repair: RepairMetadata = RepairMetadata(),
    deprecated: Boolean = false,
    replacedBy: List<String> = emptyList(),
    deprecationReason: String? = null,
): LlmRuleSpec = LlmRuleSpec(
    metadata = ruleMetadata(
        name = name,
        category = category,
        intent = intent,
        forModellers = forModellers,
        forAI = forAI,
        targetElements = targetElements,
        errorMessages = errorMessages,
        severity = severity,
        aliases = aliases,
        repair = repair,
        deprecated = deprecated,
        replacedBy = replacedBy,
        deprecationReason = deprecationReason,
    ),
)

internal class CompositeRuleBuilder {
    private val subChecks = mutableListOf<SubCheckConfig>()
    fun sub(diagnosticCode: String, check: DeterministicCheckConfig) {
        subChecks += SubCheckConfig(diagnosticCode, check)
    }

    fun build(): CompositeCheckConfig = CompositeCheckConfig(
        targetTypes = emptyList(),
        subChecks = subChecks.toList(),
    )
}

// The helper mirrors RuleMetadata's shape to keep id derivation centralized without hiding fields.
@Suppress("LongParameterList")
private fun ruleMetadata(
    name: String,
    category: RuleCategory,
    intent: String,
    forModellers: String,
    forAI: String,
    targetElements: List<String>,
    errorMessages: Map<String, String>,
    severity: RuleSeverity,
    aliases: List<String>,
    repair: RepairMetadata,
    deprecated: Boolean,
    replacedBy: List<String>,
    deprecationReason: String?,
): RuleMetadata {
    val slug = slug(name)
    return RuleMetadata(
        id = "${category.shortCode}-$slug",
        name = name,
        slug = slug,
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
}

private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
