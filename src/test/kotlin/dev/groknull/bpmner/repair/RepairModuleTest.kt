

package dev.groknull.bpmner.repair

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Action
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.generation.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.generation.internal.adapter.outbound.BpmnDefinitionToXmlConverter
import dev.groknull.bpmner.repair.BpmnRefinementFailureException
import dev.groknull.bpmner.repair.internal.domain.BpmnRefinementEngine
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnLintService
import dev.groknull.bpmner.validation.internal.adapter.outbound.BpmnXsdValidator
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.`when`
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
class RepairModuleTest {
    @MockitoBean
    private lateinit var bpmnLintService: BpmnLintService

    @MockitoBean
    private lateinit var bpmnXsdValidator: BpmnXsdValidator

    @Autowired
    private lateinit var refinementEngine: BpmnRefinementEngine

    @Test
    fun `refinement engine publishes BpmnValidationPassedEvent when BPMN is already valid`(events: PublishedEvents) {
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        doReturn(emptyList<Any>()).`when`(bpmnLintService).lint(anyString(), anyPhase())
        doReturn(null).`when`(bpmnLintService).autoFix(anyString(), anyLintIssues(), anyPhase())
        doReturn(emptyMap<String, String>()).`when`(bpmnLintService).ruleDocs(anyRuleNames())

        val request = BpmnRequest(processDescription = "Make toast")
        val definition = validDefinition()
        val graph = graph(definition)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)
        val context = FakeActionContext()

        refinementEngine.refine(request, graph, rendered, context)

        val passedEvents = events.ofType(BpmnValidationPassedEvent::class.java).toList()
        assertTrue(passedEvents.isNotEmpty(), "Expected at least one BpmnValidationPassedEvent to be published")
    }

    @Test
    fun `refinement engine publishes BpmnValidationFailedEvent before failing on invalid BPMN`(events: PublishedEvents) {
        val lintIssue = LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")
        `when`(bpmnXsdValidator.validateDetailed(anyString())).thenReturn(emptyList())
        doReturn(listOf(lintIssue)).`when`(bpmnLintService).lint(anyString(), anyPhase())
        doReturn(null).`when`(bpmnLintService).autoFix(anyString(), anyLintIssues(), anyPhase())
        doReturn(emptyMap<String, String>()).`when`(bpmnLintService).ruleDocs(anyRuleNames())

        val definition = validDefinition()
        val graph = graph(definition)
        val rendered = BpmnDefinitionToXmlConverter().render(graph)
        val context = FakeActionContext()
        context.expectResponse(definition) // repair returns same definition → unchanged patch → fails fast

        assertThrows(BpmnRefinementFailureException::class.java) {
            refinementEngine.refine(
                request = BpmnRequest(processDescription = "Make toast"),
                graph = graph,
                rendered = rendered,
                context = context,
            )
        }

        val failedEvents = events.ofType(BpmnValidationFailedEvent::class.java).toList()
        assertTrue(failedEvents.isNotEmpty(), "Expected at least one BpmnValidationFailedEvent to be published")
    }

    private fun graph(definition: BpmnDefinition): LaidOutProcessGraph {
        val composed =
            ComposedProcessGraph(
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
                    BpmnNode(
                        id = "StartEvent_1",
                        name = "Order received",
                        type = NodeType.START_EVENT,
                        bounds = BpmnBounds(80.0, 120.0, 36.0, 36.0),
                    ),
                    BpmnNode(
                        id = "Task_1",
                        name = "Toast bread",
                        type = NodeType.SERVICE_TASK,
                        bounds = BpmnBounds(180.0, 98.0, 100.0, 80.0),
                    ),
                    BpmnNode(
                        id = "EndEvent_1",
                        name = "Toast served",
                        type = NodeType.END_EVENT,
                        bounds = BpmnBounds(320.0, 120.0, 36.0, 36.0),
                    ),
                ),
            sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "StartEvent_1",
                        "Task_1",
                        waypoints =
                            listOf(
                                BpmnWaypoint(116.0, 138.0),
                                BpmnWaypoint(180.0, 138.0),
                            ),
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "Task_1",
                        "EndEvent_1",
                        waypoints =
                            listOf(
                                BpmnWaypoint(280.0, 138.0),
                                BpmnWaypoint(320.0, 138.0),
                            ),
                    ),
                ),
        )

    private fun anyString(): String = ArgumentMatchers.anyString()

    private fun anyPhase(): BpmnLintPhase = ArgumentMatchers.any(BpmnLintPhase::class.java) ?: BpmnLintPhase.FINAL_POST_LAYOUT

    private fun anyLintIssues(): List<LintIssue> = ArgumentMatchers.anyList()

    private fun anyRuleNames(): Collection<String> = ArgumentMatchers.anyCollection()

    private class FakeActionContext(
        private val delegate: FakeOperationContext = FakeOperationContext(),
    ) : ActionContext,
        OperationContext by delegate {
        override val processContext = delegate.processContext
        override val action: Action? = null
        override val toolGroups: Set<ToolGroupRequirement> get() = delegate.toolGroups
        override val operation = delegate.operation

        fun expectResponse(response: Any) = delegate.expectResponse(response)

        override fun promptRunner(
            llm: LlmOptions,
            toolGroups: Set<ToolGroupRequirement>,
            toolObjects: List<ToolObject>,
            promptContributors: List<PromptContributor>,
            contextualPromptContributors: List<ContextualPromptElement>,
            generateExamples: Boolean,
        ): PromptRunner =
            delegate.promptRunner(
                llm = llm,
                toolGroups = toolGroups,
                toolObjects = toolObjects,
                promptContributors = promptContributors,
                contextualPromptContributors = contextualPromptContributors,
                generateExamples = generateExamples,
            )
    }
}
