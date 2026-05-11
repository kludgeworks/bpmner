package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.ProcessOutline
import dev.groknull.bpmner.core.ValidatedOutline
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

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
    ],
)
class GenerationModuleTest {
    @MockitoBean
    private lateinit var agentInvoker: AgentPlatformBpmnAgentInvoker

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
        val definition = validDefinition()
        val graph = graph(definition)

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

    private fun graph(definition: BpmnDefinition): LaidOutProcessGraph {
        val composed =
            ComposedProcessGraph(
                outline = ValidatedOutline(ProcessOutline(BpmnRequest("test"), definition, OutlineMetrics(1, 0, 0, 0))),
                definition = definition,
                objectOwnersByObjectRef = emptyMap(),
            )
        return LaidOutProcessGraph(OwnedElementGraph(composed, emptyMap(), emptyMap()), definition)
    }

    private fun validDefinition() =
        BpmnDefinition(
            processId = "Process_MakeToast",
            processName = "Make toast",
            nodes =
                listOf(
                    BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
                    BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
                    BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "StartEvent_1",
                        "Task_1",
                        waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "Task_1",
                        "EndEvent_1",
                        waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
                    ),
                ),
        )
}
