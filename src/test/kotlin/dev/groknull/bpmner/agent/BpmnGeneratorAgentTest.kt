package dev.groknull.bpmner.agent

import com.embabel.agent.test.unit.FakeOperationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BpmnGeneratorAgentTest {

    @Test
    fun `valid rendered bpmn passes straight through to validated xml`() {
        val xsdValidator = RecordingXsdValidator(listOf(emptyList()))
        val lintService = RecordingLintService(listOf(emptyList()))
        val converter = RecordingConverter()
        val agent = BpmnGeneratorAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val definition = validDefinition()
        val rendered = converter.render(definition)

        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), rendered, FakeOperationContext())

        assertEquals(rendered.xml, result.xml)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(1, xsdValidator.xmls.size)
        assertEquals(1, lintService.xmls.size)
        assertEquals(1, converter.renderCalls)
    }

    @Test
    fun `lint issue with linked id triggers definition repair and full rerender revalidation`() {
        val invalid = validDefinition()
        val corrected = validDefinition()
        val xsdValidator = RecordingXsdValidator(listOf(emptyList(), emptyList()))
        val lintService = RecordingLintService(
            listOf(
                listOf(LintIssue(id = "Task_1", rule = "start-event-required", message = "Missing start event")),
                emptyList(),
            )
        )
        val converter = RecordingConverter()
        val agent = BpmnGeneratorAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeOperationContext()
        context.expectResponse(corrected)
        val initialRendered = converter.render(invalid)
        val result = agent.validateAndRefineBpmn(BpmnRequest("Make toast"), initialRendered, context)

        assertTrue(result.xml.contains("id=\"Process_MakeToast\""))
        assertTrue(result.xml.contains("id=\"Task_1\""))
        assertEquals(2, xsdValidator.xmls.size)
        assertEquals(2, lintService.xmls.size)
        assertEquals(2, converter.renderCalls)
        val repairPrompt = context.llmInvocations.single().messages.joinToString("\n") { it.content }
        assertTrue(repairPrompt.contains("elementId=Task_1"))
        assertTrue(repairPrompt.contains("objectRef=nodes[id=Task_1]"))
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
        val agent = BpmnGeneratorAgent(BpmnConfig(maxAttempts = 3), lintService, xsdValidator, converter)
        val context = FakeOperationContext()
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

    private class RecordingLintService(
        private val responses: List<List<LintIssue>?>,
    ) : BpmnLintService() {
        val xmls = mutableListOf<String>()
        private var index = 0

        override fun lint(bpmnXml: String): List<LintIssue>? {
            xmls += bpmnXml
            return responses[index++]
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

    private fun definitionWithoutStartEvent() = BpmnDefinition(
        processId = "Process_MakeToast_Invalid",
        processName = "Make toast",
        nodes = listOf(
            BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
            BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(320.0, 120.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge(
                "Flow_2",
                "Task_1",
                "EndEvent_1",
                waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(320.0, 138.0)),
            ),
        ),
    )
}
