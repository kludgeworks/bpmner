/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider

/**
 * Produces the application-wide [RuleProfile] by composing two layers:
 *
 *  1. **Named profile baseline** — computed in Kotlin from the active bean registry.
 *     `recommended` is the identity profile (no overrides, no disabled rules). `strict` bumps
 *     every WARNING-default rule to ERROR, computed on demand over the live [BeanRuleRegistry]
 *     via a lazy [ObjectProvider] (only the `strict` branch forces the registry — no eager
 *     edge, no Spring startup-order dependency). Profile name is read from `bpmner.pkl` via
 *     [BpmnerLintConfig.profile] (defaults to `recommended`).
 *  2. **User overrides** — parsed from `bpmner.pkl`'s [BpmnerLintConfig.severityOverrides].
 *     **User entries always win** over the profile's entries; the profile is the baseline,
 *     the user map is the escape hatch.
 *
 * Value parsing for both layers normalises the four legal strings — `error`, `warning`, `info`,
 * `off` (case-insensitive; `warn` is accepted as a synonym for `warning`). `off` adds the rule
 * id to [RuleProfile.disabledRuleIds]; the other three add to [RuleProfile.severityOverrides].
 *
 * **Override-key validation.** User-supplied override and disabled-rule keys are validated
 * against the live bean id set (executable rules + LLM specs). Unknown keys are reported via
 * a WARN log message and silently no-op at evaluation time. This validation runs inside
 * [ruleProfile], not during construction, so the registry is never touched eagerly.
 *
 * **Failure modes** (all loud, all fail startup):
 *  - **Unknown profile name.** The configured profile name doesn't match any built-in profile.
 *    The error message lists every available profile.
 *  - **Unknown override key.** A key in [BpmnerLintConfig.severityOverrides] doesn't match any
 *    known rule id. A WARN log message lists the offending keys — the entry silently no-ops at
 *    evaluation time (the rule id never matches anything). This is intentional: module test
 *    contexts and custom rule registries may see a subset of the full catalog, so a hard failure
 *    here would break valid partial-context startup scenarios.
 *  - **Unrecognised severity value.** A value in [BpmnerLintConfig.severityOverrides] doesn't
 *    match `error`, `warning`, `warn`, `info`, or `off` — produces a WARN log line and is
 *    otherwise ignored (startup is not failed by a single bad value; keys are stricter than
 *    values because a typo in a key silently disables nothing, while a bad value is caught by
 *    logging).
 */
internal class RuleProfileFactory(
    private val beanRegistryProvider: ObjectProvider<BeanRuleRegistry>,
) {
    private val logger = LoggerFactory.getLogger(RuleProfileFactory::class.java)

    fun ruleProfile(lintConfig: BpmnerLintConfig): RuleProfile {
        val profileName = lintConfig.profile.trim()
        check(profileName in AVAILABLE_PROFILES) {
            "Unknown rule profile '$profileName'. Available profiles: " +
                "${AVAILABLE_PROFILES.sorted().joinToString(", ")}. " +
                "Set 'profile' in bpmner.pkl to one of the available profiles."
        }

        val baseline: RuleProfile = when (profileName) {
            PROFILE_RECOMMENDED -> RuleProfile.EMPTY
            PROFILE_STRICT -> computeStrictBaseline(beanRegistryProvider.getObject())
            else -> error("Unhandled profile '$profileName' — this is a bug; update the when() branch")
        }

        val (userOverrides, userDisabled) = parseUserOverrides(lintConfig.severityOverrides)

        // Validate override keys against the live bean id set before merging.
        // Only touch the registry if there are any keys to validate — this avoids an eager
        // ObjectProvider.getObject() call when the user has no overrides configured.
        val allOverrideKeys = userOverrides.keys + userDisabled
        if (allOverrideKeys.isNotEmpty()) {
            validateOverrideKeys(allOverrideKeys, beanRegistryProvider.getObject())
        }

        // User overrides win — they're the per-deployment escape hatch on top of the profile.
        val mergedOverrides = baseline.severityOverrides + userOverrides
        val mergedDisabled = baseline.disabledRuleIds + userDisabled

        logger.info(
            "Rule profile loaded: name='{}' ({} baseline override(s), {} baseline disabled), " +
                "{} user override(s), {} user disabled",
            profileName,
            baseline.severityOverrides.size,
            baseline.disabledRuleIds.size,
            userOverrides.size,
            userDisabled.size,
        )
        return RuleProfile(severityOverrides = mergedOverrides, disabledRuleIds = mergedDisabled)
    }

    /**
     * Computes the `strict` baseline: every executable rule whose declared severity is
     * [RuleSeverity.WARNING] gets an override to [RuleSeverity.ERROR]. INFO- and ERROR-default
     * rules are left untouched. Only called when the `strict` profile is selected.
     */
    private fun computeStrictBaseline(registry: BeanRuleRegistry): RuleProfile {
        val overrides = registry.activeRules()
            .filter { it.metadata.severity == RuleSeverity.WARNING }
            .associate { it.id to RuleSeverity.ERROR }
        return RuleProfile(severityOverrides = overrides, disabledRuleIds = emptySet())
    }

    /**
     * Checks that every key in [overrideKeys] (union of severity-override ids and disabled-rule
     * ids) is a known rule id in the live bean registry (executable rules + LLM specs). Unknown
     * keys are reported via a WARN log message — they silently no-op at evaluation time (the
     * rule id never matches anything). A hard failure is not used here because Spring Modulith
     * module tests and partial-context startup scenarios may load a subset of the full catalog.
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

    private fun parseUserOverrides(
        raw: Map<String, String?>,
    ): Pair<Map<String, RuleSeverity>, Set<String>> = parseRawOverrides(
        source = "bpmner.pkl severityOverrides",
        raw = raw,
    )

    private fun parseRawOverrides(
        source: String,
        raw: Map<String, String?>,
    ): Pair<Map<String, RuleSeverity>, Set<String>> {
        val severityOverrides = mutableMapOf<String, RuleSeverity>()
        val disabledRuleIds = mutableSetOf<String>()
        for ((ruleId, value) in raw) {
            when (val normalised = value?.trim()?.lowercase()) {
                null, "" -> logger.warn(
                    "{}['{}'] has a null or empty value; ignored. Expected one of: error, warning, info, off.",
                    source,
                    ruleId,
                )

                "off" -> disabledRuleIds += ruleId

                "error" -> severityOverrides[ruleId] = RuleSeverity.ERROR

                "warning", "warn" -> severityOverrides[ruleId] = RuleSeverity.WARNING

                "info" -> severityOverrides[ruleId] = RuleSeverity.INFO

                else -> logger.warn(
                    "{}['{}'] = '{}' — unrecognised severity; ignored. Expected one of: error, warning, info, off.",
                    source,
                    ruleId,
                    normalised,
                )
            }
        }
        return severityOverrides to disabledRuleIds
    }

    companion object {
        private const val PROFILE_RECOMMENDED = "recommended"
        private const val PROFILE_STRICT = "strict"

        /** Built-in profiles. Adding a new profile requires a Kotlin `when` branch above. */
        val AVAILABLE_PROFILES: Set<String> = setOf(PROFILE_RECOMMENDED, PROFILE_STRICT)
    }
}
