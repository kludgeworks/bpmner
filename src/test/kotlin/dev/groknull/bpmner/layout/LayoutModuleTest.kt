/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `layout` module context bootstraps and exposes its root-package ports.
 *
 * BootstrapMode.ALL_DEPENDENCIES: `BpmnConfig` is **two module hops** away from `layout`
 * (`layout` → `validation` → `config`). Spring Modulith `DIRECT_DEPENDENCIES` only resolves
 * one level deep, so the `config` package is not reachable and `BpmnConfig` cannot materialise
 * under isolation. Flipping to `DIRECT_DEPENDENCIES` is a **§10 follow-on** after the
 * `llm`/`config` dependency-depth reshape shortens the chain — it is not a Modulith upgrade
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
class LayoutModuleTest {
    @Autowired
    private lateinit var bpmnLayoutPort: BpmnLayoutPort

    @Test
    fun `layout module bootstraps and exposes its layout port`() {
        assertNotNull(bpmnLayoutPort, "BpmnLayoutPort should be available in the layout module context")
    }
}
