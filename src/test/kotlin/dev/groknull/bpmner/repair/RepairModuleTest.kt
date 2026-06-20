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
 * BootstrapMode.ALL_DEPENDENCIES: the required beans are **two module hops** away from `repair`
 * (`repair` → `generation` → `contract.ProcessContractMarkdownRenderer`). Spring Modulith
 * `DIRECT_DEPENDENCIES` only resolves one level deep, so the transitive `contract` beans cannot
 * materialise under isolation. Flipping to `DIRECT_DEPENDENCIES` is a **§10 follow-on** after
 * the `llm`/`config` dependency-depth reshape shortens the chain — it is not a Modulith upgrade
 * (barred by N4). (ADR-23 Decision 1.2)
 * API keys are stubbed so no live LLM call is made at startup.
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
