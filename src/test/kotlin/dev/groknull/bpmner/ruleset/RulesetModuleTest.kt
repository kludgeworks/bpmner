/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset

import dev.groknull.bpmner.EmbabelShellTestConfiguration
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.pkl.BpmnerLintConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `ruleset` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES: `ConventionsLoader` constructor-injects `BpmnRulesUriConfig`
 * (S4: `BpmnConfig` dissolved, config now lives in the `ruleset` module itself), creating a
 * `USES_COMPONENT` edge that adds `dev.groknull.bpmner.ruleset` to the module's own bootstrap scan.
 * `@ConfigurationPropertiesScan` in the app root supplies the bean. `@ConditionalOnMissingBean`
 * on `bpmnerLintConfig` prevents double registration. No stub required. (ADR-007 Decision 1.1, S4)
 * API keys are stubbed so no live LLM call is made at startup.
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
@Import(EmbabelShellTestConfiguration::class, RulesetModuleTest.Config::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class RulesetModuleTest {
    @Autowired
    private lateinit var ruleEngine: RuleEngine

    @Autowired
    private lateinit var ruleProfile: RuleProfile

    @Test
    fun `ruleset module bootstraps and exposes its rule engine port`() {
        assertNotNull(ruleEngine, "RuleEngine should be available in the ruleset module context")
    }

    @Test
    fun `booting with default styleguide activates naming and metadata rules at error severity`() {
        assertThat(ruleProfile.severityOverrides).containsEntry("def-header-present", RuleSeverity.ERROR)
        assertThat(ruleProfile.severityOverrides).containsEntry("def-notes-present", RuleSeverity.ERROR)
        assertThat(ruleProfile.severityOverrides).containsEntry("def-legend-present", RuleSeverity.ERROR)

        assertThat(ruleProfile.disabledRuleIds).doesNotContain(
            "act-verb-object-name",
            "act-activity-label-capitalization",
            "name-no-element-type-words",
            "name-uncommon-abbreviations",
        )
    }

    @TestConfiguration
    internal class Config {
        @Bean
        @Primary
        fun testBpmnerLintConfig(): BpmnerLintConfig = defaultBpmnerLintConfig()
    }
}
