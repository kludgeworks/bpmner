/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckConfig

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
        checkPrimitive = check::class.simpleName,
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
            checkPrimitive = "COMPOSITE",
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
): LlmRuleSpec = LlmRule(
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
    private val targetTypes = mutableListOf<String>()

    fun targetTypes(vararg types: String) {
        targetTypes += types
    }

    fun sub(diagnosticCode: String, check: DeterministicCheckConfig) {
        subChecks += SubCheckConfig(diagnosticCode, check)
    }

    fun build(): CompositeCheckConfig = CompositeCheckConfig(
        targetTypes = targetTypes.toList(),
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
    checkPrimitive: String? = null,
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
        checkPrimitive = checkPrimitive,
        aliases = aliases,
        deprecated = deprecated,
        replacedBy = replacedBy,
        deprecationReason = deprecationReason,
    )
}

private fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
