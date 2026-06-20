/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

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
 * Validates that the `readiness` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `readiness` module requires `BpmnRequestPromptContributor`
 * from the `config` module (used by `BpmnReadinessAgent` for prompt construction). Spring Modulith
 * includes `BpmnConfig` automatically (via auto-configured `@ConfigurationProperties`), but the
 * port interface `BpmnRequestPromptContributor` is provided by `generation` in production and must
 * be stubbed in the `readiness`-only context. A local `@TestConfiguration` provides a no-op stub.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
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
     * Provides a no-op `BpmnRequestPromptContributor` stub for the test context.
     * In production this bean is provided by `generation`; in the `readiness`-only
     * context it is not available and must be stubbed to allow context startup.
     */
    @TestConfiguration
    class ReadinessTestConfig {
        @Bean
        fun bpmnRequestPromptContributor(): BpmnRequestPromptContributor {
            return BpmnRequestPromptContributor { _ -> PromptContributor.fixed("") }
        }
    }

    @Autowired
    private lateinit var bpmnReadinessInvoker: BpmnReadinessInvoker

    @Test
    fun `readiness module bootstraps and exposes its readiness invoker port`() {
        assertNotNull(bpmnReadinessInvoker, "BpmnReadinessInvoker should be available in the readiness module context")
    }
}
