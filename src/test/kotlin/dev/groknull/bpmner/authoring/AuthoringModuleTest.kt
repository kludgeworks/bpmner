/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `authoring` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES (intentional; see ADR-006 gate 4‴ rationale): `authoring`
 * depends on `alignment`, `contract`, `readiness`, `ruleset`, and `conformance`, producing a deep
 * transitive graph. The full pipeline must be wired together so the `BpmnGenerationAgent`'s
 * `@Action` handoff, contract extraction, and alignment checks can resolve at startup.
 * DIRECT_DEPENDENCIES would not suffice here — the transitive closure is load-bearing.
 * LLM API keys are stubbed via `@TestPropertySource` so no live LLM call is made at startup.
 * (S7 — ADR-006 gate 4‴ ALL_DEPENDENCIES rationale; ARCHITECTURE §5 S7, G8)
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
class AuthoringModuleTest {
    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `authoring module bootstraps and exposes its agent invoker`() {
        assertNotNull(bpmnAgentInvoker, "AgentPlatformBpmnAgentInvoker should be available in the authoring module context")
    }
}
