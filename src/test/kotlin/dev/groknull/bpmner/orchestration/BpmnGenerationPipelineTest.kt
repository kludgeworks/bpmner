/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.orchestration

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.prompt.PromptFixtures
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
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
import org.xmlunit.assertj.XmlAssert
import org.xmlunit.assertj.XmlAssert.assertThat

/**
 * End-to-end offline test of the generation pipeline: the LLM stages are stubbed with the canned
 * `canonical*.json` fixtures, so what actually runs is the **deterministic** transformation —
 * `composeGraph` → `render` → `layout`/XSD → `BpmnResult` assembly — wired through the orchestrator.
 *
 * It asserts **structural invariants** the canned outline implies (one process, the expected flow-node
 * and sequence-flow shape, every outline id surviving into the rendered model), not a self-minted
 * byte-golden — so it catches a deterministic stage dropping/mangling elements without being circular.
 * BPMN validity itself is already enforced inside the pipeline (the `layout` action errors on XSD
 * corruption), so it is not re-asserted here.
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
        return objectMapper.readValue(json)
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

    private fun assertXml(xml: String): XmlAssert = assertThat(xml).withNamespaceContext(NAMESPACES)

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

        val result = AgentPlatformTypedOps(platform).transform(
            PromptFixtures.canonicalRequest,
            BpmnResult::class.java,
            ProcessOptions(budget = Budget(actions = 15), ephemeral = true),
        )

        assertEquals(BpmnGenerationStatus.GENERATED, result.status)
        val xml = result.xml
        assertTrue(!xml.isNullOrBlank(), "expected non-blank BPMN xml")

        // Exactly one process, and the flow-node / sequence-flow / lane shape the canned outline implies.
        assertXml(xml!!).nodesByXPath("//bpmn:process").hasSize(1)
        assertXml(xml).nodesByXPath("//bpmn:startEvent").hasSize(1)
        assertXml(xml).nodesByXPath("//bpmn:endEvent").hasSize(2)
        assertXml(xml).nodesByXPath("//bpmn:serviceTask").hasSize(2)
        assertXml(xml).nodesByXPath("//bpmn:userTask").hasSize(1)
        assertXml(xml).nodesByXPath("//bpmn:exclusiveGateway").hasSize(1)
        assertXml(xml).nodesByXPath("//bpmn:sequenceFlow").hasSize(6)
        assertXml(xml).nodesByXPath("//bpmn:lane").hasSize(2)

        // Every id from the canned outline survives compose → render → layout into the rendered model.
        val expectedIds = listOf(
            "Process_credit_application",
            "StartEvent_1",
            "act-run-credit-check",
            "dec-score-check",
            "act-auto-approve",
            "act-underwriter-review",
            "end-approved",
            "end-reviewed",
            "Flow_1",
            "Flow_2",
            "Flow_3",
            "Flow_4",
            "Flow_5",
            "Flow_6",
        )
        expectedIds.forEach { id ->
            assertXml(xml).nodesByXPath("//*[@id='$id']").exist()
        }
    }

    private companion object {
        private val NAMESPACES = mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
        )
    }
}
