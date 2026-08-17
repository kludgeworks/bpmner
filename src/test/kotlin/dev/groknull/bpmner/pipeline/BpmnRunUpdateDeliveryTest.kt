/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.RunUpdateSinkRegistry
import dev.groknull.bpmner.prompt.PromptFixtures
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration

/**
 * Owns one concern: does a **real** run deliver the right [RunUpdate] stream to a subscriber?
 *
 * Every other RunUpdate test feeds the registry fabricated updates
 * ([dev.groknull.bpmner.pipeline.internal.adapter.inbound.RunUpdateSinkRegistry] semantics) or
 * fabricated platform events (ACL translation). This one drives the deployed
 * `BpmnGenerationAgent` itself, offline against canned stages, and reads the stream back the way
 * the SSE endpoint does — so the whole delivery path is exercised end to end, including the
 * globally-registered `BpmnRunUpdateChannel` that no test wires up explicitly.
 *
 * Deliberately runs the production agent rather than a synthetic goal agent: the run-update
 * filter admits only the deployed generation agent, and a test that invented its own agent shape
 * would force that filter to be widened for test-only reasons.
 *
 * Structural BPMN output is `BpmnGenerationPipelineTest`; GOAP planning and the clarification
 * loop are `BpmnAgentFlowSystemTest`. Neither asserts the stream.
 */
@SpringBootTest
@ActiveProfiles("offline")
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=mock-key",
        "embabel.agent.platform.models.gemini.api-key=mock-key",
        "embabel.agent.platform.models.mistralai.api-key=mock-key",
        "embabel.agent.platform.models.openai.api-key=mock-key",
    ],
)
class BpmnRunUpdateDeliveryTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var platform: AgentPlatform

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var runUpdateSinkRegistry: RunUpdateSinkRegistry

    // The production invoker spins a nested agent sub-process that is unmockable offline.
    @MockitoBean
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    @Test
    fun `a real run delivers an ordered stream ending in exactly one terminal`() {
        stubReadiness(readyAssessment())
        stubCannedStages()

        val process = runGenerationAgent(ephemeral = true)
        val updates = runUpdateSinkRegistry.subscribe(process.id).collectList().block(TIMEOUT)!!

        assertTrue(updates.isNotEmpty(), "expected at least one RunUpdate from a real run")
        updates.forEachIndexed { index, update ->
            assertEquals((index + 1).toLong(), update.seq, "seq must be strictly increasing from 1")
        }

        assertEquals(1, updates.count { it is RunUpdate.Terminal }, "exactly one terminal update")
        val terminal = updates.last()
        assertTrue(terminal is RunUpdate.Terminal, "the terminal update must be last")
        assertEquals(RunOutcome.COMPLETED, (terminal as RunUpdate.Terminal).outcome)

        // The graph exists before any XML does; a subscriber renders on that ordering.
        val graphDraftIndex = updates.indexOfFirst { it.artifactState == ArtifactState.GRAPH_DRAFT }
        val firstXmlDraftIndex = updates.indexOfFirst { it.artifactState == ArtifactState.XML_DRAFT }
        assertTrue(graphDraftIndex >= 0, "expected a GRAPH_DRAFT update on a real run")
        assertTrue(firstXmlDraftIndex >= 0, "expected an XML_DRAFT update on a real run")
        assertTrue(graphDraftIndex < firstXmlDraftIndex, "GRAPH_DRAFT must be delivered before the first XML_DRAFT")

        for (i in 1 until updates.size) {
            assertTrue(
                updates[i].phase.ordinal >= updates[i - 1].phase.ordinal,
                "phase must never regress: ${updates[i - 1].phase} -> ${updates[i].phase}",
            )
        }
    }

    @Test
    fun `the readiness verdict rides the READINESS update as a typed detail key`() {
        stubReadiness(readyAssessment())
        stubCannedStages()

        val process = runGenerationAgent(ephemeral = true)
        val updates = runUpdateSinkRegistry.subscribe(process.id).collectList().block(TIMEOUT)!!

        // The client branches on this to know whether a question follows, so it must survive a
        // real run rather than only a hand-built event.
        val readiness = updates.first { it.phase == RunPhase.READINESS }
        assertEquals(ReadinessVerdict.READY.name, readiness.detail["verdict"])
    }

    @Test
    fun `a run parked for clarification delivers an AWAITING_INPUT update`() {
        // Asserts the HITL pause is real on the delivery path, not just on process.status:
        // BpmnRunUpdateChannel is auto-registered globally, never passed via ProcessOptions here.
        stubReadiness(needsClarificationAssessment())

        val process = runGenerationAgent(ephemeral = false)
        assertEquals(AgentProcessStatusCode.WAITING, process.status)

        val awaiting = runUpdateSinkRegistry.subscribe(process.id)
            .filter { it.phase == RunPhase.AWAITING_INPUT }
            .next()
            .block(TIMEOUT)
        assertTrue(awaiting != null, "expected an AWAITING_INPUT RunUpdate when the process pauses for HITL")
        assertEquals(ArtifactState.NONE, awaiting!!.artifactState)
        assertTrue(awaiting.detail.containsKey("round"))
        assertTrue(awaiting.detail.containsKey("maxRounds"))
    }

    /**
     * Runs the deployed generation agent, the same one
     * [dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker]
     * resolves in production, synchronously so the process id and its finished stream are in hand.
     */
    private fun runGenerationAgent(ephemeral: Boolean): AgentProcess {
        val agent = platform.agents().find { it.name == GENERATION_AGENT_NAME }
            ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        return platform.createAgentProcessFrom(
            agent,
            // Headroom over the happy path, not a budget assertion; production runs far larger.
            ProcessOptions(budget = Budget(actions = 25), ephemeral = ephemeral),
            PromptFixtures.canonicalRequest,
        ).run()
    }

    private fun stubReadiness(assessment: ProcessInputAssessment) {
        `when`(readinessInvoker.assess(anyNonNull())).thenReturn(assessment)
        whenCreateObject({ true }, ProcessInputAssessment::class.java).thenReturn(assessment)
    }

    private fun stubCannedStages() {
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)
            .thenReturn(loadContractFixtureObject("canonicalContractFlat.json"))
        whenCreateObject({ true }, FlatBpmnDefinition::class.java)
            .thenReturn(loadFixtureObject("canonicalOutlineFlat.json"))
        whenCreateObject({ true }, AlignmentFindings::class.java)
            .thenReturn(loadFixtureObject("canonicalAlignment.json"))
    }

    private inline fun <reified T> loadFixtureObject(name: String): T =
        objectMapper.readValue(fixtureJson(name))

    private fun loadContractFixtureObject(name: String): Any =
        objectMapper.readValue(fixtureJson(name), FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)

    private fun fixtureJson(name: String): String =
        BpmnRunUpdateDeliveryTest::class.java.getResource("/parity/$name")?.readText()
            ?: error("Fixture not found: /parity/$name")

    // Mockito's any() returns a Java platform-typed null; passing it straight to a non-null Kotlin
    // parameter trips Kotlin's null check. Returning a genuine non-null T sidesteps it.
    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)

        // Matches BpmnRunUpdateChannel's filter and the production invoker's lookup.
        private const val GENERATION_AGENT_NAME = "BpmnGenerationAgent"

        private fun readyAssessment() = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            missingAreas = emptyList(),
            clarificationQuestions = emptyList(),
            evidence = emptyList(),
            rationale = "Mocked readiness",
        )

        private fun needsClarificationAssessment() = ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 40,
            dimensions = emptyList(),
            evidence = emptyList(),
            clarificationQuestions = listOf(
                ClarificationQuestion(
                    id = "q-end",
                    questionText = "What final state should the process reach?",
                    options = listOf("The order is completed", "The order remains open"),
                ),
            ),
            rationale = "Needs clarification",
        )
    }
}
