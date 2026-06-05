/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.generation.internal.adapter.inbound.BpmnGeneratorAgent
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode
import org.springframework.modulith.test.PublishedEvents
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

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
@MockitoBean(types = [AgentPlatformBpmnAgentInvoker::class])
class GenerationModuleTest {
    @Autowired
    private lateinit var agentInvoker: AgentPlatformBpmnAgentInvoker

    @Autowired
    private lateinit var generatorAgent: BpmnGeneratorAgent

    @Test
    fun `generation module bootstraps and exposes agent invoker`() {
        assertNotNull(agentInvoker, "AgentPlatformBpmnAgentInvoker should be available in the generation module context")
    }

    @Test
    fun `renderBpmnXml action publishes BpmnGeneratedEvent`(events: PublishedEvents) {
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition)

        generatorAgent.renderBpmnXml(BpmnRequest(processDescription = "Make toast").ready(), graph)

        val generatedEvents = events.ofType(BpmnGeneratedEvent::class.java).toList()
        assertTrue(generatedEvents.isNotEmpty(), "Expected BpmnGeneratedEvent to be published by renderBpmnXml")
        assertTrue(
            generatedEvents
                .single()
                .rendered.xml
                .contains("<process"),
            "Published event should carry rendered BPMN XML",
        )
    }

    private fun BpmnRequest.ready() = ReadyBpmnContext(
        request = this,
        assessment =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 100,
            dimensions = emptyList(),
            rationale = "Ready",
        ),
    )
}
