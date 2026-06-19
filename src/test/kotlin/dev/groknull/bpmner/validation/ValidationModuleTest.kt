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
 * BootstrapMode.ALL_DEPENDENCIES: the `validation` module depends on `rules`, which requires
 * the full `BeanRuleRegistry` Spring wiring and its `*RuleConfig` `@Configuration` beans. These
 * are transitive dependencies of `rules` (e.g. the `BpmnNlpConfig` and category rule configs).
 * ALL_DEPENDENCIES ensures the full transitive Spring context is available so every `rules` bean
 * can be wired into `validation`'s context. (S5 — ARCHITECTURE §5 S5, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
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
