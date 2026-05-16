/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.TestBpmnFixtures.testBpmnDefinition
import dev.groknull.bpmner.TestBpmnFixtures.testLaidOutGraph
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.internal.adapter.inbound.BpmnGeneratorAgent
import dev.groknull.bpmner.generation.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
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
