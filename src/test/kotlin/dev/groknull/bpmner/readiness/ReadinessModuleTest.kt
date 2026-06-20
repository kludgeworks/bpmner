/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.embabel.agent.config.annotation.EnableAgents
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `readiness` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-22 gate 4‴): Two ADR-22 decisions make this possible.
 * Decision 1 — `BpmnConfig` is registered inside `config` via `@EnableConfigurationProperties`
 * on `BpmnPipelineConfig`; it is no longer app-root-only, so it materialises under isolation.
 * Decision 2 — `AgentPlatformBpmnReadinessInvoker` constructor-injects embabel `AgentPlatform`;
 * `@EnableAgents` in the local `@TestConfiguration` supplies the real platform bean (wiring,
 * not a stub). The `BpmnRequestPromptContributor` seam has been deleted (ADR-21 Track A), so no
 * contributor stub is required. API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-22 Decisions 1+2; ARCHITECTURE §5 S7, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
@Import(ReadinessModuleTest.ReadinessTestConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class ReadinessModuleTest {
    /**
     * Supplies the real embabel `AgentPlatform` via `@EnableAgents` (ADR-22 Decision 2).
     * This is framework wiring, not a stub: it provides the actual `AgentPlatform` bean
     * scoped to the agents present in the bootstrapped modules.
     */
    @TestConfiguration
    @EnableAgents
    class ReadinessTestConfig

    @Autowired
    private lateinit var bpmnReadinessInvoker: BpmnReadinessInvoker

    @Test
    fun `readiness module bootstraps and exposes its readiness invoker port`() {
        assertNotNull(bpmnReadinessInvoker, "BpmnReadinessInvoker should be available in the readiness module context")
    }
}
