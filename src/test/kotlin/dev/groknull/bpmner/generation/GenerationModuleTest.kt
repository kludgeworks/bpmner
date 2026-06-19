/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `generation` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `generation` module depends on alignment, contract,
 * readiness, rules, and validation modules, each with their own transitive Spring beans
 * (BeanRuleRegistry, rule configs, XSD validator, contract extractor, etc.). ALL_DEPENDENCIES
 * ensures every transitive Spring bean in the full dependency graph is wired — DIRECT_DEPENDENCIES
 * would miss the transitive beans required by rules/validation/config. LLM API keys are stubbed
 * via @TestPropertySource so no live LLM call is made at startup. (S5 — ARCHITECTURE §5 S5, G8)
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
class GenerationModuleTest {
    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `generation module bootstraps and exposes its agent invoker`() {
        assertNotNull(bpmnAgentInvoker, "AgentPlatformBpmnAgentInvoker should be available in the generation module context")
    }
}
