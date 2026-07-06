/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.test.context.TestPropertySource

/**
 * Validates that the `telemetry` module context bootstraps successfully.
 *
 * BootstrapMode.ALL_DEPENDENCIES (intentional; see ADR-006 gate 4‴ rationale): `telemetry`
 * is a purely-outbound event-listener module that consumes the full pipeline event graph
 * (`BpmnGeneratedEvent`, `BpmnAlignmentCheckedEvent`, `BpmnReadinessAssessedEvent`,
 * `BpmnValidationFailedEvent`, `BpmnValidationPassedEvent`, etc.).
 * ALL_DEPENDENCIES ensures every event-type class in the transitive closure is wired so the
 * listeners can reference them at startup. DIRECT_DEPENDENCIES would not cover the full event
 * graph. The module exposes no root-package ports; context startup is the meaningful assertion.
 * API keys are stubbed so no live LLM call is made at startup.
 * (S7 — ADR-006 gate 4‴ ALL_DEPENDENCIES rationale; ARCHITECTURE §5 S7, G8; §1.8 telemetry)
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
class TelemetryModuleTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `telemetry module bootstraps successfully`() {
        assertNotNull(applicationContext, "ApplicationContext should be available — telemetry module started")
    }
}
