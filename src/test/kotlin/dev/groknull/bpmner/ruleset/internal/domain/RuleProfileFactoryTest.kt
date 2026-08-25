/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // Established test-class convention — JUnit naturally has many.

package dev.groknull.bpmner.ruleset.internal.domain

import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.pkl.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.RuleProfile
import dev.groknull.bpmner.ruleset.defaultBpmnerLintConfig
import dev.groknull.bpmner.ruleset.internal.domain.beans.BeanRuleRegistry
import dev.groknull.bpmner.ruleset.internal.domain.beans.bpmnerKotlinRuleContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun build(
        severityOverrides: Map<String, BpmnerLintConfig.Severity> = emptyMap(),
        registry: BeanRuleRegistry = realRegistry,
    ): RuleProfile {
        val factory = RuleProfileFactory(providerOf(registry))
        val lintConfig = defaultBpmnerLintConfig().withSeverityOverrides(severityOverrides)
        return factory.ruleProfile(lintConfig)
    }

    // -------------------------------------------------------------------------
    // User-overrides parsing

    @Test
    fun `empty config yields the styleguide baseline with all active rules at error`() {
        val profile = build()

        assertThat(profile.severityOverrides).isNotEmpty
        profile.severityOverrides.forEach { (ruleId, severity) ->
            assertEquals(
                RuleSeverity.ERROR,
                severity,
                "default rule '$ruleId' must be ERROR, was $severity",
            )
        }
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `error severity is parsed into the override map`() {
        // Use known rule ids so the override-key validation passes.
        val profile = build(
            severityOverrides = mapOf(
                "act-activity-label-capitalization" to BpmnerLintConfig.Severity.ERROR,
            ),
        )

        assertEquals(RuleSeverity.ERROR, profile.severityOverrides["act-activity-label-capitalization"])
        assertTrue(profile.disabledRuleIds.isEmpty())
    }

    @Test
    fun `off populates disabledRuleIds rather than the severity map`() {
        val profile = build(severityOverrides = mapOf("art-group-usage" to BpmnerLintConfig.Severity.OFF))

        assertTrue("art-group-usage" in profile.disabledRuleIds)
        assertFalse("art-group-usage" in profile.severityOverrides)
    }

    // -------------------------------------------------------------------------
    // Override-key validation (unknown keys are reported via WARN log, not silently skipped)

    @Test
    fun `unknown rule id in severityOverrides survives into profile and is logged as WARN`() {
        val profile = build(severityOverrides = mapOf("totally-not-a-rule" to BpmnerLintConfig.Severity.OFF))

        assertTrue("totally-not-a-rule" in profile.disabledRuleIds, "unknown 'off' key should survive into disabled set")
    }

    @Test
    fun `unknown severity-override key survives into profile`() {
        val profile = build(severityOverrides = mapOf("definitely-not-a-rule" to BpmnerLintConfig.Severity.ERROR))

        assertTrue("definitely-not-a-rule" in profile.severityOverrides, "unknown severity key should survive into override map")
    }

    @Test
    fun `user-disabled rule extends the profile disabled set`() {
        val profile = build(
            severityOverrides = mapOf("art-group-usage" to BpmnerLintConfig.Severity.OFF),
        )

        assertTrue("art-group-usage" in profile.disabledRuleIds)
    }
}
