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
 * BootstrapMode.ALL_DEPENDENCIES (intentional; see ADR-22 gate 4‴ rationale): `generation`
 * depends on `alignment`, `contract`, `readiness`, `rules`, and `validation`, producing a deep
 * transitive graph. The full pipeline must be wired together so the `BpmnGenerationAgent`'s
 * `@Action` handoff, contract extraction, and alignment checks can resolve at startup.
 * DIRECT_DEPENDENCIES would not suffice here — the transitive closure is load-bearing.
 * LLM API keys are stubbed via `@TestPropertySource` so no live LLM call is made at startup.
 * (S7 — ADR-22 gate 4‴ ALL_DEPENDENCIES rationale; ARCHITECTURE §5 S7, G8)
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
