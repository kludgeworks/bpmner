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
 * BootstrapMode.ALL_DEPENDENCIES (ADR-009 (bootstrap tiers) Tier 3 — deep integrator, settled post-S9):
 * `repair` is a genuine deep integrator across `authoring`, `conformance`, `contract`,
 * `readiness`, and `ruleset`. The S9 re-seam (ADR-009 (port-fronting) disposition a) has resolved the
 * root-package `internal` leak: `BpmnContractFidelityChecker` and `DefaultFlowAssigner` are
 * now in `authoring.internal.domain` behind their respective ports (`BpmnContractFidelityPort`,
 * `BpmnDefaultFlowPort`). However, a DIRECT_DEPENDENCIES flip is still not achievable:
 * the `authoring` module itself requires the `alignment` module to fully wire its beans
 * (e.g. `LlmBpmnProcessGenerator` depends on `BpmnLoggingConfig` from `conformance`, and
 * `BpmnGenerationAgent` wires `BpmnAligner` from `alignment`). Since `alignment` is not a
 * direct dependency of `repair`, a DIRECT_DEPENDENCIES bootstrap of `repair` cannot
 * fully wire `authoring`'s beans — making ALL_DEPENDENCIES the correct tier for a true
 * wiring-complete integration gate. API keys are stubbed so no live LLM call is made.
 * (S9 — ADR-009 (bootstrap tiers) Tier-3 settled rationale; ADR-009 (port-fronting) re-seam complete)
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
