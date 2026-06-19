/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `observability` module context bootstraps successfully.
 *
 * BootstrapMode.ALL_DEPENDENCIES: the `observability` module is a purely-outbound event-listener
 * module that depends on alignment, generation, readiness, repair, and validation modules, each
 * with their own transitive Spring beans. ALL_DEPENDENCIES ensures every transitive Spring bean
 * in the dependency graph is wired so the event listeners can reference the correct event types.
 * The module exposes no root-package ports; context startup is the meaningful assertion.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S5 — ARCHITECTURE §5 S5, G8; §1.8 observability is a cross-cutting sink)
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
class ObservabilityModuleTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `observability module bootstraps successfully`() {
        assertNotNull(applicationContext, "ApplicationContext should be available — observability module started")
    }
}
