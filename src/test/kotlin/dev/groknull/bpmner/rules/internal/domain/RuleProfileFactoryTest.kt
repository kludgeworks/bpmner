/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // Established test-class convention — JUnit naturally has many.

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRulesConfig
import dev.groknull.bpmner.rules.RuleProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RuleProfileFactory]. The factory composes two layers:
 *  - **Named profile baseline** loaded from `linter/pkl/profiles/{Name}Profile.pkl`.
 *  - **User overrides** parsed from `bpmner.rules.severity-overrides`.
 *
 * User entries always win on key collision.
 */
class RuleProfileFactoryTest {
    private val factory = RuleProfileFactory()

    private fun build(
        profile: String = "recommended",
        severityOverrides: Map<String, String?> = emptyMap(),
    ): RuleProfile {
        val config = BpmnConfig(
            rules = BpmnRulesConfig(profile = profile, severityOverrides = severityOverrides),
        )
        return factory.ruleProfile(config)
    }

    // -------------------------------------------------------------------------
    // User-overrides parsing (Phase 2E surface, unchanged behaviour)

    @Test
    fun `empty config yields the recommended profile with no overrides`() {
        val profile = build()

        assertTrue(profile.severityOverrides.isEmpty())
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `error_warning_info severities are parsed into the override map`() {
        val profile = build(
            severityOverrides = mapOf(
                "a-rule" to "error",
                "b-rule" to "warning",
                "c-rule" to "info",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["a-rule"])
        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["b-rule"])
        assertEquals(RuleSeverity.INFO, profile.severityOverrides["c-rule"])
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `warn is accepted as a synonym for warning`() {
        val profile = build(severityOverrides = mapOf("a-rule" to "warn"))

        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["a-rule"])
    }

    @Test
    fun `off populates disabledRuleIds rather than the severity map`() {
        val profile = build(severityOverrides = mapOf("a-rule" to "off"))

        assertTrue("a-rule" in profile.disabledRuleIds)
        assertTrue(profile.severityOverrides.isEmpty())
    }

    @Test
    fun `parsing is case-insensitive and trims whitespace`() {
        val profile = build(
            severityOverrides = mapOf(
                "a-rule" to "ERROR",
                "b-rule" to "  Warning  ",
                "c-rule" to "Off",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["a-rule"])
        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["b-rule"])
        assertTrue("c-rule" in profile.disabledRuleIds)
    }

    @Test
    fun `unrecognised severity value is logged and otherwise ignored`() {
        val profile = build(
            severityOverrides = mapOf(
                "good-rule" to "error",
                "bad-rule" to "verybad",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["good-rule"])
        assertFalse("bad-rule" in profile.severityOverrides)
        assertFalse("bad-rule" in profile.disabledRuleIds)
    }

    @Test
    fun `unknown rule ids are not validated and survive into the profile`() {
        val profile = build(severityOverrides = mapOf("totally-not-a-rule" to "off"))

        assertTrue("totally-not-a-rule" in profile.disabledRuleIds)
    }

    // -------------------------------------------------------------------------
    // Named profile loading (Phase 6 #221 surface)

    @Test
    fun `recommended profile has no severity overrides or disabled rules`() {
        // Recommended is the identity profile — declared severities are used as-is.
        val profile = build(profile = "recommended")

        assertTrue(profile.severityOverrides.isEmpty())
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `strict profile bumps every WARNING-default rule to ERROR`() {
        val profile = build(profile = "strict")

        // The Pkl strict profile is computed from RulesIndex by filtering for severity=warning
        // and emitting ERROR overrides. The exact count drifts with the catalog; we assert the
        // shape rather than a fixed number: at least one warning rule today, and every override
        // points to ERROR.
        assertTrue(profile.severityOverrides.isNotEmpty(), "strict profile should have at least one override")
        profile.severityOverrides.forEach { (ruleId, severity) ->
            assertEquals(
                RuleSeverity.ERROR,
                severity,
                "strict profile override for '$ruleId' must be ERROR, was $severity",
            )
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
        val error = assertThrows(IllegalStateException::class.java) {
            build(profile = "definitely-not-a-real-profile")
        }
        assertTrue(error.message!!.contains("definitely-not-a-real-profile"))
        assertTrue(error.message!!.contains("recommended"))
        assertTrue(error.message!!.contains("strict"))
    }

    @Test
    fun `user-disabled rule extends the profile's disabled set`() {
        val profile = build(
            profile = "recommended",
            severityOverrides = mapOf("some-rule" to "off"),
        )

        assertTrue("some-rule" in profile.disabledRuleIds)
    }
}
