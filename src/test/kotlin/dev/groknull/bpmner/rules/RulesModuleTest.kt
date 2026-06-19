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
import org.springframework.context.annotation.Primary
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `rules` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `rules` module requires `BpmnerLintConfig` (produced by
 * `ConventionsLoader` from `config.BpmnConfig`). The `config` module is not available under
 * module isolation. `spring.main.allow-bean-definition-overriding=true` is scoped to this
 * test context only (via `@TestPropertySource`; it does not affect other tests) and permits
 * `RulesTestConfig.bpmnerLintConfig()` to replace `ConventionsLoader`'s definition. `@Primary`
 * is added so that if both definitions coexist, the injector selects the test bean explicitly.
 * API keys are stubbed as a precaution for config beans. (S5 — ARCHITECTURE §5 S5, G8)
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
     * Spring Modulith's module isolation does not include `config` module beans. `@Primary`
     * ensures the injector selects this test bean when both definitions are registered
     * (REVIEW-S5 row #11 — ARCHITECTURE §5 S5, G8).
     */
    @TestConfiguration
    class RulesTestConfig {
        @Bean
        @Primary
        fun bpmnerLintConfig(): BpmnerLintConfig = BpmnerLintConfig()
    }

    @Autowired
    private lateinit var ruleEngine: RuleEngine

    @Test
    fun `rules module bootstraps and exposes its rule engine port`() {
        assertNotNull(ruleEngine, "RuleEngine should be available in the rules module context")
    }
}
