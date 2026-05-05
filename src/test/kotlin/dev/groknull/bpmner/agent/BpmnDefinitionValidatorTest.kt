package dev.groknull.bpmner.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BpmnDefinitionValidatorTest {

    @Test
    fun `validator accepts minimal valid definition`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
                BpmnNode("Task_1", "Validate request", NodeType.USER_TASK),
                BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                BpmnEdge("Flow_2", "Task_1", "EndEvent_1"),
            ),
        )

        val errors = BpmnDefinitionValidator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects missing refs and missing end event`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "MissingNode"),
            ),
        )

        val errors = BpmnDefinitionValidator.validate(definition)

        assertContains(errors.joinToString("\n"), "targetRef 'MissingNode' does not match any node id")
        assertContains(errors.joinToString("\n"), "definition must contain at least one END_EVENT")
    }
}
