/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

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
 * Validates that the `contract` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-22 gate 4‴): Two ADR-22 decisions make this possible.
 * Decision 1 — `BpmnConfig` is registered inside `config` via `@EnableConfigurationProperties`
 * on `BpmnPipelineConfig`; it materialises whenever `config` is in the bootstrap set.
 * Decision 2 — `contract` grants `readiness`, which bootstraps `AgentPlatformBpmnReadinessInvoker`
 * (a ctor-injected `AgentPlatform`); `@EnableAgents` supplies the real platform bean (wiring,
 * not a stub). The `BpmnRequestPromptContributor` seam has been deleted (ADR-21 Track A).
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-22 Decisions 1+2; ARCHITECTURE §5 S7, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
@Import(ContractModuleTest.ContractTestConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class ContractModuleTest {
    /**
     * Supplies the real embabel `AgentPlatform` via `@EnableAgents` (ADR-22 Decision 2).
     * This is framework wiring, not a stub: it provides the actual `AgentPlatform` bean
     * scoped to the agents present in the bootstrapped modules.
     */
    @TestConfiguration
    @EnableAgents
    class ContractTestConfig

    @Autowired
    private lateinit var processContractExtractor: ProcessContractExtractor

    @Test
    fun `contract module bootstraps and exposes its contract extractor port`() {
        assertNotNull(processContractExtractor, "ProcessContractExtractor should be available in the contract module context")
    }
}
