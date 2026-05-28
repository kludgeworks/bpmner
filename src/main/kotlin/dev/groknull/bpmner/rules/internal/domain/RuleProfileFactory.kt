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
import org.pkl.core.PklException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.IOException
import java.net.URI

/**
 * Produces the application-wide [RuleProfile] by composing two layers:
 *
 *  1. **Named profile baseline** — loaded from `linter/pkl/profiles/{Name}Profile.pkl` based on
 *     `bpmner.rules.profile` (defaults to `recommended`). `RecommendedProfile.pkl` is the
 *     identity profile; `StrictProfile.pkl` bumps every WARNING-default rule to ERROR via the
 *     Pkl `amends` chain.
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
 * **Failure modes** (both loud, both fail startup):
 *  - **Unknown profile name.** The configured profile name doesn't match any
 *    `linter/pkl/profiles/{Name}Profile.pkl` resource on the classpath. The error message lists
 *    every discoverable profile.
 *  - **Malformed profile module.** The profile resource exists but Pkl can't evaluate it
 *    (syntax error, missing import, type-constraint violation). The underlying [PklException]
 *    is chained as the cause; the message names the URI and the offending profile.
 *
 * A malformed entry in the user's severity-overrides map produces a WARN log line and is
 * otherwise ignored — startup is never failed by a typo in one map entry. Profile-file errors
 * are stricter because the catalog itself is structurally wrong, not just one entry.
 */
@Configuration
internal class RuleProfileFactory {
    private val logger = LoggerFactory.getLogger(RuleProfileFactory::class.java)
    private val resourceResolver = PathMatchingResourcePatternResolver(javaClass.classLoader)

    @Bean
    fun ruleProfile(config: BpmnConfig): RuleProfile {
        val profileName = config.rules.profile.trim()
        val available = discoverAvailableProfiles()
        if (profileName !in available) {
            throw IllegalStateException(
                "Unknown rule profile '$profileName'. Available profiles: " +
                    "${available.sorted().joinToString(", ")}. " +
                    "Drop a {Name}Profile.pkl file into linter/pkl/profiles/ to add a new one.",
            )
        }
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

    // Walks the classpath for `linter/pkl/profiles/{Name}Profile.pkl` resources and returns the
    // set of available profile names — the filename's stem with the `Profile.pkl` suffix stripped
    // and the first letter lowercased. Replaces a hand-maintained list that would silently
    // diverge from the Bazel `profiles/*.pkl` glob.
    private fun discoverAvailableProfiles(): Set<String> {
        val resources = try {
            resourceResolver.getResources("classpath*:$PROFILE_RESOURCE_PATTERN")
        } catch (e: IOException) {
            logger.warn("Could not enumerate profile resources on the classpath", e)
            return emptySet()
        }
        return resources
            .mapNotNull { it.filename }
            .filter { it.endsWith(PROFILE_FILENAME_SUFFIX) }
            .map { it.removeSuffix(PROFILE_FILENAME_SUFFIX).replaceFirstChar { c -> c.lowercase() } }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun loadNamedProfile(name: String): RuleProfile {
        // Existence has already been verified by `discoverAvailableProfiles` in `ruleProfile`.
        // Any failure here is a real evaluation problem — surface the Pkl detail, do NOT mask
        // it as "unknown profile" (which would send a developer chasing a name typo when the
        // real cause is a syntax error or bad import inside the module).
        val uri = profileUri(name)
        val pkl = try {
            ConfigEvaluator.preconfigured().forKotlin().use { evaluator ->
                evaluator.evaluate(ModuleSource.uri(URI.create(uri)))
            }
        } catch (e: PklException) {
            throw IllegalStateException(
                "Failed to evaluate rule profile '$name' from $uri. The module is on the classpath " +
                    "but could not be parsed or evaluated. Inspect the file for syntax errors, " +
                    "missing imports, or type-constraint violations.",
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
        private const val PROFILE_RESOURCE_PATTERN = "linter/pkl/profiles/*Profile.pkl"
        private const val PROFILE_FILENAME_SUFFIX = "Profile.pkl"
        private const val PROFILE_URI_TEMPLATE = "modulepath:/linter/pkl/profiles/%sProfile.pkl"

        private fun profileUri(name: String): String = PROFILE_URI_TEMPLATE.format(name.replaceFirstChar { it.titlecase() })
    }
}
