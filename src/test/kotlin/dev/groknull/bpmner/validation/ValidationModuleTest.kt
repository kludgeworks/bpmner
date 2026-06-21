/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `validation` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-22 gate 4‴): `BpmnConfig` is now registered inside
 * the `config` module via `@EnableConfigurationProperties(BpmnConfig::class)` on
 * `BpmnPipelineConfig` (ADR-22 Decision 1), so it materialises whenever `config` is in the
 * bootstrap set. `ConventionsLoader.bpmnerLintConfig` is guarded with `@ConditionalOnMissingBean`
 * so no stub is required. No platform agent is bootstrapped here. API keys are stubbed so no
 * live LLM call is made at startup. (S7 — ADR-22 Decisions 1; ARCHITECTURE §5 S7, G8)
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
class ValidationModuleTest {
    @Autowired
    private lateinit var lintingPort: BpmnLintingPort

    @Autowired
    private lateinit var xsdValidationPort: BpmnXsdValidationPort

    @Test
    fun `validation module bootstraps and exposes its ports`() {
        assertNotNull(lintingPort, "BpmnLintingPort should be available in the validation module context")
        assertNotNull(xsdValidationPort, "BpmnXsdValidationPort should be available in the validation module context")
    }
}
