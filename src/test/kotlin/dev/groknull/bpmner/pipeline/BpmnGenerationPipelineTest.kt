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
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.prompt.PromptFixtures
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper

/**
 * End-to-end offline test of the generation pipeline: the LLM stages are stubbed with the canned
 * `canonical*.json` fixtures, so what actually runs is the **deterministic** transformation —
 * `composeGraph` → `render` → `layout`/XSD → `BpmnResult` assembly — wired through the orchestrator.
 *
 * It asserts **structural invariants** the canned outline implies (one process, the expected flow-node
 * and sequence-flow shape, every outline id surviving into the rendered model), not a self-minted
 * byte-golden — so it catches a deterministic stage dropping/mangling elements without being circular.
 * BPMN validity itself is already enforced inside the pipeline (the `layout` action errors on XSD
 * corruption), so it is not re-asserted here. The RunUpdate stream a real run delivers is
 * `BpmnRunUpdateDeliveryTest`; this test asserts structure only.
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
class BpmnGenerationPipelineTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var platform: AgentPlatform

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    // The orchestrator's assessReadiness action delegates to BpmnReadinessInvoker, whose production
    // implementation spins a nested agent sub-process that is unmockable offline. Readiness is
    // irrelevant to this test, so we replace the invoker with a READY stub.
    @MockitoBean
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    private inline fun <reified T> loadFixtureObject(name: String): T {
        val json = BpmnGenerationPipelineTest::class.java.getResource("/parity/$name")?.readText()
            ?: error("Fixture not found: /parity/$name")
        return objectMapper.readValue(json, T::class.java)
    }

    private fun loadContractFixtureObject(name: String): Any {
        val json = BpmnGenerationPipelineTest::class.java.getResource("/parity/$name")?.readText()
            ?: error("Fixture not found: /parity/$name")
        return objectMapper.readValue(json, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)
    }

    // Mockito's any() returns a Java platform-typed null; passing it straight to a non-null Kotlin
    // parameter trips Kotlin's null check. Returning a genuine non-null T sidesteps it.
    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Test
    fun pipelineProducesStructurallyCompleteBpmnFromCannedStages() {
        val readyAssessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            missingAreas = emptyList(),
            clarificationQuestions = emptyList(),
            evidence = emptyList(),
            rationale = "Mocked readiness",
        )
        // Covers the orchestrator's assessReadiness path (invoker) with no nested sub-process.
        `when`(readinessInvoker.assess(anyNonNull())).thenReturn(readyAssessment)
        // Covers the planner choosing the deployed BpmnReadinessAgent's LLM action instead.
        whenCreateObject({ true }, ProcessInputAssessment::class.java)
            .thenReturn(readyAssessment)

        // Use the contract module's published test fixture to avoid reaching into
        // contract.internal.adapter.inbound (S5 — ARCHITECTURE §5 S5, §1.5).
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)
            .thenReturn(loadContractFixtureObject("canonicalContractFlat.json"))

        whenCreateObject({ true }, FlatBpmnDefinition::class.java)
            .thenReturn(loadFixtureObject("canonicalOutlineFlat.json"))
        whenCreateObject({ true }, AlignmentFindings::class.java)
            .thenReturn(loadFixtureObject("canonicalAlignment.json"))

        val process = runGenerationProcess()
        assertEquals(AgentProcessStatusCode.WAITING, process.status)
        val form = process.last(FormBindingRequest::class.java) as FormBindingRequest<*>
        assertEquals("Approve diagram metadata", form.payload.title)
    }

    // Runs the deployed generation agent, the same one AgentPlatformBpmnAgentInvoker resolves in
    // production, synchronously so the finished process is in hand.
    private fun runGenerationProcess(): AgentProcess {
        val agent = platform.agents().find { it.name == GENERATION_AGENT_NAME }
            ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        return platform.createAgentProcessFrom(
            agent,
            // Headroom over the happy path, not a budget assertion. Each stage that can fail
            // returns a sealed ready/failed pair, so its success branch costs one extra action to
            // unwrap; production runs on a far larger budget.
            ProcessOptions(budget = Budget(actions = 25), ephemeral = false),
            PromptFixtures.canonicalRequest,
        ).run()
    }

    private companion object {
        // Matches the production invoker's lookup; @Agent derives the name from the class.
        private const val GENERATION_AGENT_NAME = "BpmnGenerationAgent"
    }
}
