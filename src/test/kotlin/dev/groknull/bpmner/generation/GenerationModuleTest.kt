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
    ],
)
@MockitoBean(types = [AgentPlatformBpmnAgentInvoker::class])
class GenerationModuleTest {
    @Autowired
    private lateinit var generationUseCase: BpmnGenerationUseCase

    @Autowired
    private lateinit var generatorAgent: BpmnGeneratorAgent

    @Test
    fun `generation module bootstraps and exposes BpmnGenerationUseCase`() {
        assertNotNull(generationUseCase, "BpmnGenerationUseCase should be available in the generation module context")
    }

    @Test
    fun `renderBpmnXml action publishes BpmnGeneratedEvent`(events: PublishedEvents) {
        val definition = testBpmnDefinition()
        val graph = testLaidOutGraph(definition)

        generatorAgent.renderBpmnXml(BpmnRequest(processDescription = "Make toast"), graph)

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
}
