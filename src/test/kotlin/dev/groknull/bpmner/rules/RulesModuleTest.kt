/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `rules` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES: `ConventionsLoader` constructor-injects `BpmnConfig`,
 * creating a `USES_COMPONENT` edge that adds `dev.groknull.bpmner.config` to the module's
 * bootstrap scan. `@EnableConfigurationProperties(BpmnConfig::class)` on `BpmnPipelineConfig`
 * supplies the bean; `@ConditionalOnMissingBean` on `bpmnerLintConfig` prevents double
 * registration. No stub required. (ADR-23 Decision 1.1)
 * API keys are stubbed so no live LLM call is made at startup.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class RulesModuleTest {
    @Autowired
    private lateinit var ruleEngine: RuleEngine

    @Test
    fun `rules module bootstraps and exposes its rule engine port`() {
        assertNotNull(ruleEngine, "RuleEngine should be available in the rules module context")
    }
}
