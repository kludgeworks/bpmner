/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.rules.RuleProfile
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Produces the application-wide [RuleProfile] from `bpmner.rules.severity-overrides`.
 *
 * Input shape: `Map<String, String?>` keyed by bare rule id (e.g. `act-verb-object-name`),
 * value is one of `error` / `warning` / `info` / `off` (case-insensitive; `warn` is accepted
 * as a synonym for `warning`). The four values are parsed into the structured profile:
 *
 *  - `off` → adds the rule id to [RuleProfile.disabledRuleIds]; the engine skips evaluation
 *  - `error` / `warning` / `info` → adds the rule id to [RuleProfile.severityOverrides]
 *
 * **Logging contract.** Unrecognised severity values, null values, and empty-string values
 * produce a WARN log line and are otherwise ignored — startup is never failed by a malformed
 * config entry. **Unknown rule ids are not validated here**; they survive into the profile
 * and silently never match anything at evaluation time. The factory deliberately does not
 * consult [dev.groknull.bpmner.rules.RuleRegistry] so the rule-config pipeline has no
 * startup-order dependency on rule loading.
 *
 * Phase 2E ships a single application-wide profile. Phase 6 (#221) introduces named profiles
 * (`recommended` / `strict`) and richer selection; this factory is the canonical extension
 * point when that lands.
 */
@Configuration
internal class RuleProfileFactory {
    private val logger = LoggerFactory.getLogger(RuleProfileFactory::class.java)

    @Bean
    fun ruleProfile(config: BpmnConfig): RuleProfile {
        val severityOverrides = mutableMapOf<String, RuleSeverity>()
        val disabledRuleIds = mutableSetOf<String>()
        for ((ruleId, raw) in config.rules.severityOverrides) {
            when (val normalised = raw?.trim()?.lowercase()) {
                null, "" -> logger.warn(
                    "bpmner.rules.severity-overrides['{}'] has a null or empty value; ignored. " +
                        "Expected one of: error, warning, info, off.",
                    ruleId,
                )

                "off" -> disabledRuleIds += ruleId

                "error" -> severityOverrides[ruleId] = RuleSeverity.ERROR

                "warning", "warn" -> severityOverrides[ruleId] = RuleSeverity.WARNING

                "info" -> severityOverrides[ruleId] = RuleSeverity.INFO

                else -> logger.warn(
                    "bpmner.rules.severity-overrides['{}'] = '{}' — unrecognised severity; " +
                        "ignored. Expected one of: error, warning, info, off.",
                    ruleId,
                    normalised,
                )
            }
        }
        if (severityOverrides.isNotEmpty() || disabledRuleIds.isNotEmpty()) {
            logger.info(
                "Rule profile loaded: {} severity override(s), {} disabled rule(s)",
                severityOverrides.size,
                disabledRuleIds.size,
            )
        }
        return RuleProfile(severityOverrides = severityOverrides, disabledRuleIds = disabledRuleIds)
    }
}
