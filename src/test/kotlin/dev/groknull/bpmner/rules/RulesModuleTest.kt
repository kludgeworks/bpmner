/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `rules` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `rules` module's Spring context requires `BpmnerLintConfig`
 * (produced by `ConventionsLoader` from `config.BpmnConfig`). Spring Modulith does not
 * automatically include the `config` module's beans in the `rules` isolation context. A local
 * `@TestConfiguration` provides `BpmnerLintConfig` directly with defaults, bypassing the
 * `ConventionsLoader` factory and avoiding the need for `BpmnConfig` from `config`.
 * API keys are stubbed as a precaution for config beans.
 * (S5 — ARCHITECTURE §5 S5, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
@Import(RulesModuleTest.RulesTestConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
class RulesModuleTest {
    /**
     * Provides a default `BpmnerLintConfig` bean for the test context, bypassing the
     * `ConventionsLoader` factory method that requires `BpmnConfig` from the `config` module.
     * Spring Modulith's module isolation does not include `config` module beans unless they are
     * detected as module-level dependencies via bean wiring.
     */
    @TestConfiguration
    class RulesTestConfig {
        @Bean
        fun bpmnerLintConfig(): BpmnerLintConfig = BpmnerLintConfig()
    }

    @Autowired
    private lateinit var ruleEngine: RuleEngine

    @Test
    fun `rules module bootstraps and exposes its rule engine port`() {
        assertNotNull(ruleEngine, "RuleEngine should be available in the rules module context")
    }
}
