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
 * BootstrapMode.ALL_DEPENDENCIES: `layout` grants `validation` which grants `rules`. Since
 * `rules`'s `DIRECT_DEPENDENCIES` bootstrap does not include the `config` package (see
 * `BLOCKER-S7.md` §5.B), `BpmnConfig` is not available transitively and `layout` cannot
 * flip to `DIRECT_DEPENDENCIES`. The `rules`/`config` scan gap (BLOCKER-S7.md §5.B)
 * blocks the flip; the test remains on `ALL_DEPENDENCIES` pending an architect decision.
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
class LayoutModuleTest {
    @Autowired
    private lateinit var bpmnLayoutPort: BpmnLayoutPort

    @Test
    fun `layout module bootstraps and exposes its layout port`() {
        assertNotNull(bpmnLayoutPort, "BpmnLayoutPort should be available in the layout module context")
    }
}
