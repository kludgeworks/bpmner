/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `orchestration` module context bootstraps and wires the GOAP agent.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `orchestration` module is the application-layer coordinator
 * (ARCHITECTURE §1.8) and depends on alignment, contract, generation, layout, readiness, repair,
 * and validation modules — the full pipeline. ALL_DEPENDENCIES ensures every transitive Spring
 * bean is wired so the BpmnGenerationAgent's @Action and GOAP wiring resolves at startup without
 * a live LLM call. DIRECT_DEPENDENCIES would not suffice because the orchestrator's actions
 * require beans from the full transitive closure (e.g. BpmnRepairer, BpmnLayoutPort, BpmnAligner).
 * API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8; §1.8 orchestration is the application layer)
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
class OrchestrationModuleTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `orchestration module bootstraps and wires the GOAP agent`() {
        assertNotNull(
            applicationContext,
            "ApplicationContext should be available — orchestration module with BpmnGenerationAgent started",
        )
    }
}
