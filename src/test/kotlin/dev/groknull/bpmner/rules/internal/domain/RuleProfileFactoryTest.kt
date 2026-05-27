/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRulesConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [RuleProfileFactory]. The factory parses the four-value
 * `error | warning | info | off` vocabulary into the structured [RuleProfile].
 * Unknown severity values produce a single WARN log line and are otherwise ignored.
 */
class RuleProfileFactoryTest {
    private val factory = RuleProfileFactory()

    private fun build(severityOverrides: Map<String, String>): dev.groknull.bpmner.rules.RuleProfile {
        val config = BpmnConfig(rules = BpmnRulesConfig(severityOverrides = severityOverrides))
        return factory.ruleProfile(config)
    }

    @Test
    fun `empty config yields the EMPTY profile`() {
        val profile = build(emptyMap())

        assertTrue(profile.severityOverrides.isEmpty())
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `error_warning_info severities are parsed into the override map`() {
        val profile = build(
            mapOf(
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
        val profile = build(mapOf("a-rule" to "warn"))

        assertEquals(RuleSeverity.WARNING, profile.severityOverrides["a-rule"])
    }

    @Test
    fun `off populates disabledRuleIds rather than the severity map`() {
        val profile = build(mapOf("a-rule" to "off"))

        assertTrue("a-rule" in profile.disabledRuleIds)
        assertTrue(profile.severityOverrides.isEmpty())
    }

    @Test
    fun `parsing is case-insensitive and trims whitespace`() {
        val profile = build(
            mapOf(
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
        // Mixed config: one good entry, one bad. The factory builds without throwing; the bad
        // entry doesn't appear in either output set.
        val profile = build(
            mapOf(
                "good-rule" to "error",
                "bad-rule" to "verybad",
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["good-rule"])
        assertTrue("bad-rule" !in profile.severityOverrides)
        assertTrue("bad-rule" !in profile.disabledRuleIds)
    }

    @Test
    fun `unknown rule ids are not validated and survive into the profile`() {
        // The factory doesn't touch RuleRegistry — config can reference rule ids the registry
        // doesn't know about. Those ids stay in the profile and silently never match anything
        // at evaluation time. This avoids a startup-order coupling between rules and config.
        val profile = build(mapOf("totally-not-a-rule" to "off"))

        assertTrue("totally-not-a-rule" in profile.disabledRuleIds)
    }
}
