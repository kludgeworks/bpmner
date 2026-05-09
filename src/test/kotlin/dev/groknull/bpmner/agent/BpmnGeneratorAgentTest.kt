package dev.groknull.bpmner.agent

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.ContextualPromptElement
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.test.unit.FakeOperationContext
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Action
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.prompt.PromptContributor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BpmnGeneratorAgentTest {

    private fun buildAgent(
        config: BpmnConfig,
        lintService: BpmnLintService,
        xsdValidator: BpmnXsdValidator,
        converter: BpmnDefinitionToXmlConverter,
        layoutService: BpmnLayoutService = RecordingLayoutService(),
    ): BpmnGeneratorAgent {
        val workflow = BpmnRefinementWorkflow(
            config = config,
            bpmnLintService = lintService,
            bpmnXsdValidator = xsdValidator,
            bpmnConverter = converter,
            bpmnDefinitionValidator = BpmnDefinitionValidator(),
        )
        return BpmnGeneratorAgent(config, converter, workflow, layoutService)
    }

    @Test
    fun `valid rendered bpmn passes straight through to validated xml`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val layoutService = RecordingLayoutService()
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter, layoutService)
        val definition = validDefinition()
        val rendered = converter.render(definition)

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), rendered, FakeActionContext())

        assertEquals(rendered.xml, result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(0, result.repairAttempts)
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `lint issue with linked id triggers definition repair and full rerender revalidation`() {
        val invalid = validDefinition()
        val corrected = validDefinition(processName = "Make toast correctly")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                emptyList(),
            )
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(invalid)
        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)

        assertTrue(result.xml.contains("id=\"Process_MakeToast\""))
        assertTrue(result.xml.contains("id=\"Task_1\""))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
        assertEquals(2, converter.renderCalls)
        assertEquals(1, result.repairAttempts)
        val repairPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(repairPrompt.contains("elementId=Task_1"))
        assertTrue(repairPrompt.contains("objectRef=nodes[id=Task_1]"))
    }

    @Test
    fun `lint parse error for unknown rule aborts without repair`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(
                    LintIssue(
                        id = null,
                        rule = "parse-error",
                        message = "unknown rule <klmact-01-verb-object-name>",
                    )
                )
            )
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        val initialRendered = converter.render(validDefinition())

        val error = assertFailsWith<BpmnValidatorInfrastructureException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)
        }

        assertTrue(error.message!!.contains("BPMN validator infrastructure failure"))
        assertTrue(error.message!!.contains("klmact-01-verb-object-name"))
        assertTrue(context.llmInvocations.isEmpty())
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `klm lint issue includes matching rule docs in repair prompt contributor`() {
        val invalid = validDefinition()
        val corrected = validDefinition(processName = "Make toast correctly")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            responses = listOf(
                listOf(LintIssue(id = "Task_1", rule = "klm/gen-02-no-duplicate-diagrams", message = "Duplicate BPMNDiagram")),
                emptyList(),
            ),
            docs = mapOf(
                "klm/gen-02-no-duplicate-diagrams" to "# gen-02-no-duplicate-diagrams\n\nDiagram docs"
            ),
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(invalid)

        agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)

        val promptContributions = context.llmInvocations.single().interaction.promptContributors.joinToString("\n") {
            it.contribution()
        }
        assertTrue(promptContributions.contains("KLM lint rule documentation for current violations"))
        assertTrue(promptContributions.contains("# gen-02-no-duplicate-diagrams"))
    }

    @Test
    fun `xsd issue is preserved as diagnostic and causes rerender before succeeding`() {
        val initial = validDefinition()
        val corrected = validDefinition(
            processId = "Process_Fixed",
            processName = "Prepare toast safely",
        )
        val xsdValidator = RecordingXsdValidator(
            listOf(
                listOf(XsdValidationIssue("cvc-complex-type failure near Task_1", "Task_1")),
                emptyList(),
            )
        )
        val lintService = RecordingLintService(listOf(emptyList()))
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)

        assertTrue(result.xml.contains("id=\"Process_Fixed\""))
        assertTrue(result.xml.contains("name=\"Prepare toast safely\""))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        val repairPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(repairPrompt.contains("source=xsd"))
        assertTrue(repairPrompt.contains("elementId=Task_1"))
        assertTrue(repairPrompt.contains("objectRef=nodes[id=Task_1]"))
    }

    @Test
    fun `workflow refinement still fails after configured max attempts`() {
        val initial = validDefinition()
        val corrected = validDefinition(processName = "Make toast again")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Still missing start event")),
            )
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 2), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)
        }

        assertTrue(error.message!!.contains("Failed to produce valid BPMN after 2 attempts"))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
    }

    @Test
    fun `unchanged diagnostics fail fast after one repair cycle`() {
        val initial = validDefinition()
        val corrected = validDefinition(processName = "Make toast again")
        val repeatedIssue = LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(listOf(listOf(repeatedIssue), listOf(repeatedIssue)))
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)
        }

        assertTrue(error.message!!.contains("unchanged diagnostics"))
        assertTrue(error.message!!.contains("history=#1"))
        assertEquals(1, context.llmInvocations.size)
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
    }

    @Test
    fun `unchanged repaired definition fails before rerender`() {
        val initial = validDefinition()
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(
            listOf(listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")))
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(initial)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)
        }

        assertTrue(error.message!!.contains("unchanged patch"))
        assertEquals(1, context.llmInvocations.size)
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `previous invalid definition output fails before another validation pass`() {
        val initial = validDefinition()
        val firstRepair = validDefinition(processName = "Make toast again")
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                listOf(LintIssue(id = "Task_1", rule = "end-event-required", message = "Missing end event")),
            )
        )
        val converter = RecordingConverter()
        val agent = buildAgent(BpmnConfig(maxAttempts = 5), lintService, xsdValidator, converter)
        val context = FakeActionContext()
        context.expectResponse(firstRepair)
        context.expectResponse(initial)
        val initialRendered = converter.render(initial)

        val error = assertFailsWith<IllegalStateException> {
            agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)
        }

        assertTrue(error.message!!.contains("repeated invalid output"))
        assertEquals(2, context.llmInvocations.size)
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
        assertEquals(2, converter.renderCalls)
    }

    @Test
    fun `validate and refine action fires only once`() {
        val action = BpmnGeneratorAgent::class.java.methods.single {
            it.name == "validateAndRefineBpmn" && it.parameterCount == 4
        }.getAnnotation(com.embabel.agent.api.annotation.Action::class.java)

        assertEquals(ActionRetryPolicy.FIRE_ONCE, action.actionRetryPolicy)
    }

    private class RecordingLayoutService(
        private val responses: List<String> = emptyList(),
    ) : BpmnLayoutService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun layout(xml: String): String {
            xmls += xml
            return if (index < responses.size) responses[index++] else xml
        }
    }

    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
        private val docs: Map<String, String> = emptyMap(),
    ) : BpmnLintService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun lint(bpmnXml: String): List<LintIssue>? {
            xmls += bpmnXml
            return responses[index++]
        }

        override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> =
            buildMap {
                ruleNames.distinct().forEach { ruleName ->
                    docs[ruleName]?.let { put(ruleName, it) }
                }
            }
    }

    private class RecordingXsdValidator(
        private val responses: List<List<XsdValidationIssue>>,
    ) : BpmnXsdValidator() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun validateDetailed(bpmnXml: String): List<XsdValidationIssue> {
            xmls += bpmnXml
            return responses[index++]
        }
    }

    private class RecordingConverter : BpmnDefinitionToXmlConverter() {
        var renderCalls = 0

        override fun render(definition: BpmnDefinition): RenderedBpmn {
            renderCalls += 1
            return super.render(definition)
        }
    }

    private class FakeActionContext(
        private val delegate: FakeOperationContext = FakeOperationContext(),
    ) : ActionContext, OperationContext by delegate {
        override val processContext = delegate.processContext
        override val action: Action? = null
        override val toolGroups: Set<ToolGroupRequirement>
            get() = delegate.toolGroups
        override val operation = delegate.operation

        val llmInvocations
            get() = delegate.llmInvocations

        fun expectResponse(response: Any) {
            delegate.expectResponse(response)
        }

        override fun promptRunner(
            llm: LlmOptions,
            toolGroups: Set<ToolGroupRequirement>,
            toolObjects: List<ToolObject>,
            promptContributors: List<PromptContributor>,
            contextualPromptContributors: List<ContextualPromptElement>,
            generateExamples: Boolean,
        ): PromptRunner = delegate.promptRunner(
            llm = llm,
            toolGroups = toolGroups,
            toolObjects = toolObjects,
            promptContributors = promptContributors,
            contextualPromptContributors = contextualPromptContributors,
            generateExamples = generateExamples,
        )
    }

    private fun validDefinition(
        processId: String = "Process_MakeToast",
        processName: String = "Make toast",
    ) = BpmnDefinition(
        processId = processId,
        processName = processName,
        nodes = listOf(
            BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
        ),
        sequences = listOf(
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
