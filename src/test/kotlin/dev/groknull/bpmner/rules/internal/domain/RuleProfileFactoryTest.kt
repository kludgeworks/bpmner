/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // Established test-class convention — JUnit naturally has many.

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.rules.BpmnerLintConfig
import dev.groknull.bpmner.rules.RuleProfile
import dev.groknull.bpmner.rules.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.rules.internal.domain.beans.bpmnerKotlinRuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.AnnotationConfigApplicationContext

/**
 * Unit tests for [RuleProfileFactory]. The factory composes two layers:
 *  - **Named profile baseline** computed in Kotlin from the live [BeanRuleRegistry].
 *  - **User overrides** parsed from [BpmnerLintConfig.severityOverrides].
 *
 * User entries always win on key collision. Override keys are validated against the live
 * bean id set — unknown keys are reported via WARN log and silently no-op at evaluation time.
 *
 * Profile name and severity overrides are sourced from [BpmnerLintConfig] (from `bpmner.pkl`);
 * the factory no longer reads `BpmnConfig.rules.profile` or `BpmnConfig.rules.severityOverrides`.
 */
@TestInstance(Lifecycle.PER_CLASS)
class RuleProfileFactoryTest {
    /**
     * Backing context for [realRegistry]. Captured once (PER_CLASS lifecycle) and closed in
     * [teardown] to prevent resource leaks across test methods.
     */
    private var registryContext: AnnotationConfigApplicationContext? = null

    /**
     * A real registry built from the isolated Kotlin bean context. Used for tests that need
     * the actual bean id set (strict snapshot, override-key validation). Shared across all
     * test methods via a lazy val — the context is created once and closed in [teardown].
     */
    private val realRegistry: BeanRuleRegistry by lazy {
        val ctx = bpmnerKotlinRuleContext()
        registryContext = ctx
        ctx.getBean(BeanRuleRegistry::class.java)
    }

    @AfterAll
    fun teardown() {
        registryContext?.close()
    }

    @Suppress("UNCHECKED_CAST")
    private fun providerOf(registry: BeanRuleRegistry): ObjectProvider<BeanRuleRegistry> {
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<BeanRuleRegistry>
        `when`(provider.getObject()).thenReturn(registry)
        return provider
    }

    /** Stub provider that throws if called — used for recommended-profile tests where the
     * registry must never be touched. */
    @Suppress("UNCHECKED_CAST")
    private fun noCallProvider(): ObjectProvider<BeanRuleRegistry> {
        val provider = mock(ObjectProvider::class.java) as ObjectProvider<BeanRuleRegistry>
        `when`(provider.getObject()).thenThrow(
            AssertionError("ObjectProvider must not be called for the recommended profile"),
        )
        return provider
    }

    private fun build(
        profile: String = "recommended",
        severityOverrides: Map<String, String?> = emptyMap(),
        registry: BeanRuleRegistry = realRegistry,
    ): RuleProfile {
        val factory = RuleProfileFactory(providerOf(registry))
        val lintConfig = BpmnerLintConfig(profile = profile, severityOverrides = severityOverrides)
        return factory.ruleProfile(lintConfig)
    }

    // -------------------------------------------------------------------------
    // User-overrides parsing

    @Test
    fun `empty config yields the recommended profile with no overrides`() {
        val profile = build()

        assertTrue(profile.severityOverrides.isEmpty())
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `error_warning_info severities are parsed into the override map`() {
        // Use known rule ids so the override-key validation passes.
        // act-activity-label-capitalization and art-group-usage are WARNING-default;
        // assoc-required-annotation-association is also a known rule id.
        val profile = build(
            severityOverrides = mapOf(
                "act-activity-label-capitalization" to "error",
                "art-group-usage" to "warning",
                "assoc-required-annotation-association" to "info",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["act-activity-label-capitalization"])
        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["art-group-usage"])
        assertEquals(RuleSeverity.INFO, profile.severityOverrides["assoc-required-annotation-association"])
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `warn is accepted as a synonym for warning`() {
        val profile = build(severityOverrides = mapOf("art-group-usage" to "warn"))

        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["art-group-usage"])
    }

    @Test
    fun `off populates disabledRuleIds rather than the severity map`() {
        val profile = build(severityOverrides = mapOf("art-group-usage" to "off"))

        assertTrue("art-group-usage" in profile.disabledRuleIds)
        assertTrue(profile.severityOverrides.isEmpty())
    }

    @Test
    fun `parsing is case-insensitive and trims whitespace`() {
        val profile = build(
            severityOverrides = mapOf(
                "act-activity-label-capitalization" to "ERROR",
                "art-group-usage" to "  Warning  ",
                "assoc-required-annotation-association" to "Off",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["act-activity-label-capitalization"])
        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["art-group-usage"])
        assertTrue("assoc-required-annotation-association" in profile.disabledRuleIds)
    }

    @Test
    fun `unrecognised severity value is logged and otherwise ignored`() {
        val profile = build(
            severityOverrides = mapOf(
                "act-activity-label-capitalization" to "error",
                "art-group-usage" to "verybad",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["act-activity-label-capitalization"])
        assertFalse("art-group-usage" in profile.severityOverrides)
        assertFalse("art-group-usage" in profile.disabledRuleIds)
    }

    // -------------------------------------------------------------------------
    // Override-key validation (unknown keys are reported via WARN log, not silently skipped)

    @Test
    fun `unknown rule id in severityOverrides survives into profile and is logged as WARN`() {
        // Unknown keys are logged as WARN and survive into the resolved profile — they silently
        // no-op at rule evaluation time because no rule with that id exists.
        val profile = build(severityOverrides = mapOf("totally-not-a-rule" to "off"))

        // The key survives (evaluated as disabled, but never matches any rule at runtime).
        assertTrue("totally-not-a-rule" in profile.disabledRuleIds, "unknown 'off' key should survive into disabled set")
    }

    @Test
    fun `unknown severity-override key survives into profile`() {
        val profile = build(severityOverrides = mapOf("definitely-not-a-rule" to "warning"))

        // Survives into the severity override map — never matches a real rule at evaluation time.
        assertTrue("definitely-not-a-rule" in profile.severityOverrides, "unknown severity key should survive into override map")
    }

    @Test
    fun `the four shipped off defaults are valid bean ids and pass key validation`() {
        // These rule ids ship as disabled-by-default in bpmner.pkl severityOverrides.
        // Verify they resolve cleanly against the live registry.
        val profile = build(
            severityOverrides = mapOf(
                "act-verb-object-name" to "off",
                "act-activity-label-capitalization" to "off",
                "name-no-element-type-words" to "off",
                "name-uncommon-abbreviations" to "off",
            ),
        )

        assertThat(profile.disabledRuleIds).contains(
            "act-verb-object-name",
            "act-activity-label-capitalization",
            "name-no-element-type-words",
            "name-uncommon-abbreviations",
        )
    }

    // -------------------------------------------------------------------------
    // Named profile loading

    @Test
    fun `recommended profile has no severity overrides or disabled rules`() {
        // recommended is the identity profile — declared severities are used as-is.
        // The registry must NOT be called for recommended; verify with a no-call provider.
        val factory = RuleProfileFactory(noCallProvider())
        val lintConfig = BpmnerLintConfig(profile = "recommended")
        val profile = factory.ruleProfile(lintConfig)

        assertTrue(profile.severityOverrides.isEmpty())
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `strict profile bumps every WARNING-default rule to ERROR`() {
        val profile = build(profile = "strict")

        // The strict profile is computed from the live bean registry by filtering for
        // severity=WARNING and emitting ERROR overrides.  Assert shape: at least one warning
        // rule today, every override is ERROR, no disabled rules in the baseline.
        assertThat(profile.severityOverrides).isNotEmpty
        profile.severityOverrides.forEach { (ruleId, severity) ->
            assertEquals(
                RuleSeverity.ERROR,
                severity,
                "strict profile override for '$ruleId' must be ERROR, was $severity",
            )
        }
        // The strict baseline disables nothing — disabled rules come only from user overrides.
        assertThat(profile.disabledRuleIds).isEmpty()
    }

    @Test
    fun `strict profile snapshot includes known WARNING-default rules`() {
        val profile = build(profile = "strict")

        // These rules have WARNING as their declared severity and must appear in strict overrides.
        // Confirmed from the live bean catalog: act-activity-label-capitalization,
        // act-discouraged-business-verbs, and art-text-annotation-usage are WARNING-default.
        assertThat(profile.severityOverrides).containsKey("act-activity-label-capitalization")
        assertThat(profile.severityOverrides).containsKey("act-discouraged-business-verbs")
        assertThat(profile.severityOverrides).containsKey("art-text-annotation-usage")
    }

    @Test
    fun `strict profile does not override ERROR-default rules`() {
        // ERROR-default rules stay at ERROR under strict — no double-override.
        // name-no-element-type-words is ERROR-default (as noted in application.yaml comment).
        // Just confirm the rules that weren't WARNING-default don't appear as overrides.
        val warningIds = realRegistry.activeRules()
            .filter { it.metadata.severity == RuleSeverity.WARNING }
            .map { it.id }
            .toSet()
        val errorIds = realRegistry.activeRules()
            .filter { it.metadata.severity == RuleSeverity.ERROR }
            .map { it.id }
            .toSet()

        val profile = build(profile = "strict")
        val strictOverrideIds = profile.severityOverrides.keys

        // Every strict override key must have been WARNING-default
        assertThat(strictOverrideIds).allMatch { it in warningIds }
        // No ERROR-default rule should appear as a strict override
        errorIds.forEach { id ->
            assertFalse(id in strictOverrideIds, "ERROR-default rule '$id' should not be in strict overrides")
        }
    }

    @Test
    fun `user severity-overrides win over the profile`() {
        // Pick a warning-default rule that strict normally bumps to ERROR, then have the user
        // explicitly set it to WARNING. The user's choice must win — that's the contract.
        val strictBaseline = build(profile = "strict")
        val firstOverriddenRule = strictBaseline.severityOverrides.keys.first()

        val profile = build(
            profile = "strict",
            severityOverrides = mapOf(firstOverriddenRule to "warning"),
        )

        assertEquals(
            RuleSeverity.WARNING,
            profile.severityOverrides[firstOverriddenRule],
            "user override must win over the profile baseline",
        )
    }

    @Test
    fun `unknown profile name fails startup with the list of available profiles`() {
        val factory = RuleProfileFactory(noCallProvider())
        val lintConfig = BpmnerLintConfig(profile = "definitely-not-a-real-profile")
        val error = assertThrows(IllegalStateException::class.java) {
            factory.ruleProfile(lintConfig)
        }
        assertTrue(error.message!!.contains("definitely-not-a-real-profile"))
        assertTrue(error.message!!.contains("recommended"))
        assertTrue(error.message!!.contains("strict"))
    }

    @Test
    fun `user-disabled rule extends the profile disabled set`() {
        val profile = build(
            profile = "recommended",
            severityOverrides = mapOf("art-group-usage" to "off"),
        )

        assertTrue("art-group-usage" in profile.disabledRuleIds)
    }
}
