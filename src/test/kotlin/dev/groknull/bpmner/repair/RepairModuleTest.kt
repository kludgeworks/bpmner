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
 * BootstrapMode.ALL_DEPENDENCIES: `repair` grants `rules`, and `rules`'s `DIRECT_DEPENDENCIES`
 * bootstrap does not include the `config` package (see `BLOCKER-S7.md` §5.B — Spring Modulith
 * 1.4.1 does not add `dev.groknull.bpmner.config` to the scan set for the `rules` module even
 * though `rules/RulesModule` declares `config` in its `allowedDependencies`). `BpmnConfig` is
 * therefore not available when `repair` bootstraps `rules` under isolation. Remains on
 * `ALL_DEPENDENCIES` until the architect resolves the `rules`/`config` bootstrap gap.
 * API keys are stubbed so no live LLM call is made at startup. (S7 deferred — BLOCKER-S7.md §5.B)
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
