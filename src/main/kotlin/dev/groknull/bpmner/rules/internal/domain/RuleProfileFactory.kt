/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.rules.RuleProfile
import org.pkl.config.java.ConfigEvaluator
import org.pkl.config.kotlin.forKotlin
import org.pkl.config.kotlin.to
import org.pkl.core.ModuleSource
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

/**
 * Produces the application-wide [RuleProfile] by composing two layers:
 *
 *  1. **Named profile baseline** — loaded from `linter/pkl/profiles/{Name}Profile.pkl` based on
 *     `bpmner.rules.profile` (defaults to `recommended`). Phase 6 (#221) ships `recommended`
 *     (each rule's declared severity wins, nothing disabled) and `strict` (every WARNING-default
 *     rule bumped to ERROR via the Pkl `amends` chain in [StrictProfile.pkl][]).
 *  2. **User overrides** — parsed from `bpmner.rules.severity-overrides`. **User entries always
 *     win** over the profile's entries; the profile is the baseline, the YAML map is the escape
 *     hatch. Unknown rule ids survive into the profile and silently never match anything at
 *     evaluation time — this factory deliberately does not consult [RuleRegistry] so the
 *     rule-config pipeline has no startup-order dependency on rule loading.
 *
 * Value parsing for both layers normalises the four legal strings — `error`, `warning`, `info`,
 * `off` (case-insensitive; `warn` is accepted as a synonym for `warning`). `off` adds the rule
 * id to [RuleProfile.disabledRuleIds]; the other three add to [RuleProfile.severityOverrides].
 *
 * **Failure modes.** An unknown profile name fails startup with the list of available profiles
 * (loud configuration errors are better than silent fallbacks). A malformed entry in the user's
 * severity-overrides map produces a WARN log line and is otherwise ignored — startup is never
 * failed by a typo in one map entry.
 */
@Configuration
internal class RuleProfileFactory {
    private val logger = LoggerFactory.getLogger(RuleProfileFactory::class.java)

    @Bean
    fun ruleProfile(config: BpmnConfig): RuleProfile {
        val profileName = config.rules.profile.trim()
        val baseline = loadNamedProfile(profileName)
        val (userOverrides, userDisabled) = parseUserOverrides(config.rules.severityOverrides)

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

    private fun loadNamedProfile(name: String): RuleProfile {
        val uri = profileUri(name)
        val pkl = try {
            ConfigEvaluator.preconfigured().forKotlin().use { evaluator ->
                evaluator.evaluate(ModuleSource.uri(URI.create(uri)))
            }
        } catch (e: org.pkl.core.PklException) {
            // The Pkl runtime reports an unresolved module with a specific message; treat any
            // load failure as "unknown profile" — that's the failure mode operators will hit.
            throw IllegalStateException(
                "Unknown rule profile '$name'. Available profiles: ${availableProfiles().joinToString(", ")}. " +
                    "Drop a {Name}Profile.pkl file into linter/pkl/profiles/ to add a new one.",
                e,
            )
        }
        val severityOverridesRaw: Map<String, String> = pkl.get("severityOverrides").to()
        val disabledRuleIdsRaw: List<String> = pkl.get("disabledRuleIds").to()

        val (overrides, disabled) = parseRawOverrides(
            source = "profile '$name'",
            raw = severityOverridesRaw.mapValues<String, String, String?> { it.value },
        )
        return RuleProfile(
            severityOverrides = overrides,
            disabledRuleIds = disabled + disabledRuleIdsRaw.toSet(),
        )
    }

    private fun parseUserOverrides(
        raw: Map<String, String?>,
    ): Pair<Map<String, RuleSeverity>, Set<String>> = parseRawOverrides(
        source = "bpmner.rules.severity-overrides",
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
        private const val PROFILE_URI_TEMPLATE = "modulepath:/linter/pkl/profiles/%sProfile.pkl"

        private fun profileUri(name: String): String = PROFILE_URI_TEMPLATE.format(name.replaceFirstChar { it.titlecase() })

        // Phase 6 (#221) ships two profiles. New profiles added via {Name}Profile.pkl files in
        // linter/pkl/profiles/ should also be listed here so the failure-mode message stays
        // accurate. Kept hand-maintained on purpose — Pkl module discovery from the classpath
        // at runtime is not portable across all launchers, and the list is short.
        internal val KNOWN_PROFILES: List<String> = listOf("recommended", "strict")

        internal fun availableProfiles(): List<String> = KNOWN_PROFILES
    }
}
