/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain

import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.pkl.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.RuleProfile
import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider

/**
 * Produces the application-wide [RuleProfile] by composing two layers:
 *
 *  1. **Styleguide baseline** — computed in Kotlin from the active bean registry.
 *     All active rules are promoted to [RuleSeverity.ERROR] (on/off model).
 *  2. **User overrides** — parsed from `bpmner.pkl`'s [BpmnerLintConfig.severityOverrides].
 *     **User entries always win** over the baseline; the user map is the escape hatch.
 *
 * Value parsing for both layers normalises the legal strings — `error`, `off`.
 * `off` adds the rule id to [RuleProfile.disabledRuleIds]; `error` adds to [RuleProfile.severityOverrides].
 *
 * **Override-key validation.** User-supplied override and disabled-rule keys are validated
 * against the live bean id set (executable rules + LLM specs). Unknown keys are reported via
 * a WARN log message and silently no-op at evaluation time.
 */
internal class RuleProfileFactory(
    private val beanRegistryProvider: ObjectProvider<BeanRuleRegistry>,
) {
    private val logger = LoggerFactory.getLogger(RuleProfileFactory::class.java)

    fun ruleProfile(lintConfig: BpmnerLintConfig): RuleProfile {
        val baseline = computeStrictBaseline(beanRegistryProvider.getObject())

        val (userOverrides, userDisabled) = parseUserOverrides(lintConfig.severityOverrides)

        // Validate override keys against the live bean id set before merging.
        val allOverrideKeys = userOverrides.keys + userDisabled
        if (allOverrideKeys.isNotEmpty()) {
            validateOverrideKeys(allOverrideKeys, beanRegistryProvider.getObject())
        }

        // User overrides win
        val mergedDisabled = baseline.disabledRuleIds + userDisabled
        val mergedOverrides = (baseline.severityOverrides + userOverrides) - mergedDisabled

        logger.info(
            "Rule profile loaded: {} baseline override(s), {} baseline disabled, " +
                "{} user override(s), {} user disabled",
            baseline.severityOverrides.size,
            baseline.disabledRuleIds.size,
            userOverrides.size,
            userDisabled.size,
        )
        return RuleProfile(severityOverrides = mergedOverrides, disabledRuleIds = mergedDisabled)
    }

    /**
     * Computes the styleguide baseline: every active rule gets an override to [RuleSeverity.ERROR].
     */
    private fun computeStrictBaseline(registry: BeanRuleRegistry): RuleProfile {
        val overrides = registry.activeRules()
            .associate { it.id to RuleSeverity.ERROR }
        return RuleProfile(severityOverrides = overrides, disabledRuleIds = emptySet())
    }

    /**
     * Checks that every key in [overrideKeys] (union of severity-override ids and disabled-rule
     * ids) is a known rule id in the live bean registry. Unknown keys are reported via a WARN log.
     */
    private fun validateOverrideKeys(overrideKeys: Set<String>, registry: BeanRuleRegistry) {
        val knownIds = (registry.activeRules().map { it.id } + registry.llmRuleSpecs().map { it.metadata.id }).toSet()
        val unknown = overrideKeys - knownIds
        if (unknown.isNotEmpty()) {
            logger.warn(
                "bpmner.pkl severityOverrides contains unknown rule id(s): {}. " +
                    "These entries will silently no-op at evaluation time.",
                unknown.sorted().joinToString(", "),
            )
        }
    }

    private fun parseUserOverrides(raw: Map<String, BpmnerLintConfig.Severity>): Pair<Map<String, RuleSeverity>, Set<String>> {
        val severityOverrides = mutableMapOf<String, RuleSeverity>()
        val disabledRuleIds = mutableSetOf<String>()
        for ((ruleId, value) in raw) {
            when (value) {
                BpmnerLintConfig.Severity.OFF -> disabledRuleIds += ruleId
                BpmnerLintConfig.Severity.ERROR -> severityOverrides[ruleId] = RuleSeverity.ERROR
            }
        }
        return severityOverrides to disabledRuleIds
    }
}
