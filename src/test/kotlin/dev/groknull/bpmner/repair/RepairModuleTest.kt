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
 * BootstrapMode.ALL_DEPENDENCIES (ADR-451-9 Tier 3 — deep integrator): `repair` is a genuine
 * deep integrator across `authoring`, `conformance`, `contract`, `readiness`, and `ruleset`.
 * Its transitive dependency set includes the two root-package `internal` types
 * `authoring.DefaultFlowAssigner` and `authoring.BpmnContractFidelityChecker` that S9
 * (ADR-451-8) will relocate to `*.internal.*` and re-seam. Flipping `repair` to
 * `DIRECT_DEPENDENCIES` before that encapsulation re-seam lands would require mocking types
 * that S9 is about to move — pure churn. The flip is deferred to S9 (§4 table line 369;
 * §4.1 S9 "Done when" lines 615–618; ADR-451-9 lines 972–979).
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-451-9; ARCHITECTURE §5 S7; flip deferred to S9)
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
