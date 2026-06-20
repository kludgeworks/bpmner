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
 * BootstrapMode.ALL_DEPENDENCIES: ADR-22 Decision 1 (`@EnableConfigurationProperties(BpmnConfig::class)`
 * on `BpmnPipelineConfig`) was intended to register `BpmnConfig` under module isolation and flip
 * this test to `DIRECT_DEPENDENCIES`. In practice, Spring Modulith 1.4.1 does not include the
 * `config` base package in the `DIRECT_DEPENDENCIES` bootstrap set for the `rules` module (see
 * `BLOCKER-S7.md` §5.B finding — `config` appears in the scan only for modules that also grant
 * `validation` or have a direct import path via the scanned public package). Until this is resolved
 * by the architect, the test remains on `ALL_DEPENDENCIES`. The `@ConditionalOnMissingBean` on
 * `ConventionsLoader.bpmnerLintConfig` (ADR-20 §9 Track B) is in place and ready to activate.
 * API keys are stubbed so no live LLM call is made at startup. (S7 deferred — BLOCKER-S7.md §5.B)
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
     * Provides a `BpmnerLintConfig` bean for the test context, bypassing the
     * `ConventionsLoader` factory method that requires `BpmnConfig`. `@Primary` ensures
     * the injector selects this bean when overriding is enabled (the bean ordering means
     * `ConventionsLoader.bpmnerLintConfig` is registered before this `@TestConfiguration`
     * bean; `allow-bean-definition-overriding=true` + `@Primary` resolves the conflict).
     * `@ConditionalOnMissingBean` on `ConventionsLoader.bpmnerLintConfig` is in place
     * (ADR-20 §9 Track B B0b) but cannot be used here due to bean-registration ordering
     * in `@ApplicationModuleTest` (test config is imported after component scan).
     * Removal of this stub is deferred pending the `rules`/`config` bootstrap gap
     * resolution — see BLOCKER-S7.md §5.B. (S7 deferred)
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
