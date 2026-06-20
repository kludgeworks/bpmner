/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `repair` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `repair` module depends on contract, generation, rules,
 * and validation modules, each with their own transitive Spring beans (BeanRuleRegistry, rule
 * configs, XSD validator, BpmnDefinitionToXmlConverter, etc.). ALL_DEPENDENCIES ensures every
 * transitive Spring bean is wired. API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8)
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
class RepairModuleTest {
    @Autowired
    private lateinit var bpmnRepairer: BpmnRepairer

    @Test
    fun `repair module bootstraps and exposes its repairer port`() {
        assertNotNull(bpmnRepairer, "BpmnRepairer should be available in the repair module context")
    }
}
