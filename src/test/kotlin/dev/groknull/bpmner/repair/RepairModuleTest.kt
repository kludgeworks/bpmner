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
 * Its transitive dependency set includes two root-package `internal` types
 * `authoring.DefaultFlowAssigner` and `authoring.BpmnContractFidelityChecker` that cross
 * module boundaries without `*.internal.*` packaging (ADR-451-8). Mocking that transitive
 * set requires stubs of types whose encapsulation boundary is not yet enforced, making this
 * a genuine Tier-3 deep-integrator test rather than a Tier-2 isolation gate.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-451-9; ARCHITECTURE §5 S7)
 * TODO(#451-9): re-evaluate flip to DIRECT_DEPENDENCIES once ADR-451-8 encapsulation re-seam lands
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
