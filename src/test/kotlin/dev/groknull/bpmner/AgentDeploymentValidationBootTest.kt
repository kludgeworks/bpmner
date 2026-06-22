/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.AgentDeploymentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * Full-app regression guard: boots the entire platform and asserts every deployed bpmner agent
 * passes the platform's static GOAP validation.
 */
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class AgentDeploymentValidationBootTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var deploymentValidator: AgentDeploymentValidator

    @Test
    fun `every deployed bpmner agent passes static GOAP validation`() {
        val ourAgents = deploymentValidator.bpmnerAgents()
        val deployedNames = ourAgents.map { it.name }.toSet()

        // Guard against a vacuous pass: the full roster must actually be deployed and scanned.
        assertEquals(
            EXPECTED_AGENTS,
            deployedNames,
            "Deployed bpmner agent roster changed; update EXPECTED_AGENTS if this is intentional",
        )

        val failures = deploymentValidator.validationFailures(ourAgents)
        assertTrue(failures.isEmpty(), "Agents failed static validation:\n${failures.joinToString("\n")}")
    }

    private companion object {
        val EXPECTED_AGENTS = setOf(
            "BpmnGenerationAgent",
            "BpmnLayoutAgent",
            "BpmnReadinessAgent",
        )
    }
}
