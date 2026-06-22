/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `pipeline` module context bootstraps and wires the GOAP agent.
 *
 * BootstrapMode.ALL_DEPENDENCIES (intentional; see ADR-22 gate 4‴ rationale): `pipeline`
 * is the full-pipeline coordinator (ARCHITECTURE §1.8). It depends on alignment, contract,
 * generation, layout, readiness, repair, and validation — the complete GOAP action graph.
 * ALL_DEPENDENCIES ensures every transitive bean is wired so `BpmnGenerationAgent`'s `@Action`
 * and GOAP wiring resolve at startup. DIRECT_DEPENDENCIES would not suffice because the
 * orchestrator's actions require beans from the full transitive closure (e.g. `BpmnRepairer`,
 * `BpmnLayoutPort`, `BpmnAligner`). `AgentDeploymentValidator` is now in
 * `pipeline.internal.adapter.inbound` (ADR-22 Track A) and resolves without a stub.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-22 gate 4‴ ALL_DEPENDENCIES rationale; ARCHITECTURE §5 S7, G8; §1.8 pipeline)
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
class PipelineModuleTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `pipeline module bootstraps and wires the GOAP agent`() {
        assertNotNull(
            applicationContext,
            "ApplicationContext should be available — pipeline module with BpmnGenerationAgent started",
        )
    }
}
