/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

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
 * Validates that the `alignment` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `alignment` module depends on contract, readiness, and
 * validation modules, each with their own transitive Spring beans. ALL_DEPENDENCIES ensures
 * every transitive bean is wired. `BpmnRequestPromptContributor` is provided by `generation`
 * in production but is not in alignment's declared `allowedDependencies`; a no-op test stub
 * is supplied so the aligner's context can start without a live `generation` bean.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8)
 */
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES, verifyAutomatically = false)
@Import(AlignmentModuleTest.AlignmentTestConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class AlignmentModuleTest {
    /**
     * Provides a no-op `BpmnRequestPromptContributor` stub. In production this is supplied
     * by `generation`; it is not in `alignment`'s `allowedDependencies` but is required
     * by `LlmBpmnAligner`'s constructor for prompt-contribution wiring.
     */
    @TestConfiguration
    class AlignmentTestConfig {
        @Bean
        fun bpmnRequestPromptContributor(): BpmnRequestPromptContributor {
            return BpmnRequestPromptContributor { _ -> PromptContributor.fixed("") }
        }
    }

    @Autowired
    private lateinit var bpmnAligner: BpmnAligner

    @Test
    fun `alignment module bootstraps and exposes its aligner port`() {
        assertNotNull(bpmnAligner, "BpmnAligner should be available in the alignment module context")
    }
}
