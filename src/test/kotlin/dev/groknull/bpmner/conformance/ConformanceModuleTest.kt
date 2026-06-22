/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `conformance` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-22 gate 4‴): `BpmnLoggingConfig` and `BpmnConformanceConfig`
 * are now owned by the `conformance` module itself (S4: `config` module dissolved). `@ConfigurationPropertiesScan`
 * in the app root supplies them; they materialise under isolation because they live in the `conformance`
 * module. `ConventionsLoader.bpmnerLintConfig` is guarded with `@ConditionalOnMissingBean` so no stub
 * is required. No platform agent is bootstrapped here. API keys are stubbed so no live LLM call is
 * made at startup. (S7 — ADR-22 Decisions 1; ARCHITECTURE §5 S7, G8; S4 — config dissolution)
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
class ConformanceModuleTest {
    @Autowired
    private lateinit var lintingPort: BpmnLintingPort

    @Autowired
    private lateinit var xsdValidationPort: BpmnXsdValidationPort

    @Test
    fun `conformance module bootstraps and exposes its ports`() {
        assertNotNull(lintingPort, "BpmnLintingPort should be available in the conformance module context")
        assertNotNull(xsdValidationPort, "BpmnXsdValidationPort should be available in the conformance module context")
    }
}
