/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

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
 * Validates that the `alignment` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.DIRECT_DEPENDENCIES (ADR-006): Two decisions make this possible.
 * 1. Decoupled capability configuration (ADR-009) — config is registered via `@ConfigurationPropertiesScan`
 *    in the app root and materialises whenever `alignment` is in the bootstrap set.
 * 2. Agent platform wiring (ADR-006) — `@EnableAgents` supplies the real platform bean.
 * API keys are stubbed so no live LLM call is made at startup.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES, verifyAutomatically = false)
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
     * Supplies the real embabel `AgentPlatform` via `@EnableAgents` (ADR-006 Decision 2).
     * This is framework wiring, not a stub: it provides the actual `AgentPlatform` bean
     * scoped to the agents present in the bootstrapped modules.
     */
    @TestConfiguration
    @EnableAgents
    class AlignmentTestConfig

    @Autowired
    private lateinit var bpmnAligner: BpmnAligner

    @Test
    fun `alignment module bootstraps and exposes its aligner port`() {
        assertNotNull(bpmnAligner, "BpmnAligner should be available in the alignment module context")
    }
}
