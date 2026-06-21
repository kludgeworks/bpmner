/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.spi.validation.GoapPathToCompletionValidator
import com.example.embabel.fixtures.CyclicallyInvalidAgent
import com.example.embabel.fixtures.TriviallyValidAgent
import dev.groknull.bpmner.orchestration.internal.adapter.inbound.AgentDeploymentValidationException
import dev.groknull.bpmner.orchestration.internal.adapter.inbound.AgentDeploymentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Proves the fail-fast deployment guard actually fails: a deliberately-invalid agent makes
 * [AgentDeploymentValidator.validateOnStartup] throw, a valid agent passes, and a foreign
 * (non-bpmner) agent is excluded even when invalid. This guarantees the guard is neither vacuous nor
 * over-eager — the mechanism that would have caught the original `NO_PATH_TO_GOAL` defect is
 * exercised directly on real validation output.
 *
 * The `@Agent` fixtures live in `com.example.embabel.fixtures` so they are never component-scanned;
 * `validationFailures` is package-agnostic (it validates whatever it is given), while the
 * startup/filter behaviour is exercised by pointing the validator's owned-package prefix at the
 * fixtures' package (or at the real bpmner prefix, to prove exclusion).
 */
class AgentDeploymentValidatorTest {
    private val reader = AgentMetadataReader()

    private fun metadata(instance: Any): Agent = reader.createAgentMetadata(instance) as? Agent
        ?: error("createAgentMetadata returned a non-agent scope for ${instance::class}")

    private fun validatorOver(
        ownedPrefix: String,
        vararg deployed: Agent,
    ): AgentDeploymentValidator {
        val platform = mock(AgentPlatform::class.java)
        `when`(platform.agents()).thenReturn(deployed.toList())
        return AgentDeploymentValidator(platform, ownedPrefix, GoapPathToCompletionValidator())
    }

    @Test
    fun `validationFailures reports NO_PATH_TO_GOAL for an unreachable goal`() {
        val invalid = metadata(CyclicallyInvalidAgent())

        val failures = validatorOver(FIXTURE_PREFIX).validationFailures(listOf(invalid))

        assertEquals(1, failures.size, "expected exactly one failing agent, got $failures")
        assertTrue(failures.single().contains("NO_PATH_TO_GOAL"), "expected NO_PATH_TO_GOAL, got ${failures.single()}")
        assertTrue(failures.single().contains(invalid.name), "failure should name the agent")
    }

    @Test
    fun `validationFailures is empty for a reachable goal`() {
        val valid = metadata(TriviallyValidAgent())

        assertTrue(validatorOver(FIXTURE_PREFIX).validationFailures(listOf(valid)).isEmpty())
    }

    @Test
    fun `validateOnStartup throws when a deployed owned agent is invalid`() {
        val failure =
            assertThrows<AgentDeploymentValidationException> {
                validatorOver(FIXTURE_PREFIX, metadata(CyclicallyInvalidAgent())).validateOnStartup()
            }
        assertTrue(failure.message!!.contains("NO_PATH_TO_GOAL"))
    }

    @Test
    fun `validateOnStartup passes when every deployed owned agent is valid`() {
        assertDoesNotThrow {
            validatorOver(FIXTURE_PREFIX, metadata(TriviallyValidAgent())).validateOnStartup()
        }
    }

    @Test
    fun `validateOnStartup ignores agents outside the owned package even when invalid`() {
        // Same invalid agent, but the validator now owns the real bpmner prefix — the fixture's
        // com.example goal names do not match, so it is filtered out and must NOT fail startup. This
        // guards against the framework's own scanned agents (com.embabel.example) breaking our boot.
        assertDoesNotThrow {
            validatorOver("dev.groknull.bpmner", metadata(CyclicallyInvalidAgent())).validateOnStartup()
        }
    }

    private companion object {
        const val FIXTURE_PREFIX = "com.example.embabel.fixtures"
    }
}
