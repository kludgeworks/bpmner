/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.config.BpmnRequestPromptContributor
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `contract` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `contract` module depends on `config` and `domain`,
 * which in turn require transitive beans (e.g. LLM-platform config beans). `BpmnRequestPromptContributor`
 * is provided by `generation` in production but is not in contract's `allowedDependencies`;
 * a no-op test stub is supplied so `LlmProcessContractExtractor`'s context can start.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
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
     * Provides a no-op `BpmnRequestPromptContributor` stub. In production this is supplied
     * by `generation`; it is not in `contract`'s `allowedDependencies` but is required
     * by `LlmProcessContractExtractor`'s constructor for prompt-contribution wiring.
     */
    @TestConfiguration
    class ContractTestConfig {
        @Bean
        fun bpmnRequestPromptContributor(): BpmnRequestPromptContributor {
            return BpmnRequestPromptContributor { _ -> PromptContributor.fixed("") }
        }
    }

    @Autowired
    private lateinit var processContractExtractor: ProcessContractExtractor

    @Test
    fun `contract module bootstraps and exposes its contract extractor port`() {
        assertNotNull(processContractExtractor, "ProcessContractExtractor should be available in the contract module context")
    }
}
