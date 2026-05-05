package dev.groknull.bpmner.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BpmnDefinitionValidatorTest {

    private val validator = BpmnDefinitionValidator()

    private val startBounds = BpmnBounds(x = 80.0, y = 120.0, width = 36.0, height = 36.0)
    private val taskBounds = BpmnBounds(x = 180.0, y = 98.0, width = 100.0, height = 80.0)
    private val endBounds = BpmnBounds(x = 340.0, y = 120.0, width = 36.0, height = 36.0)
    private val standardWaypoints = listOf(
        BpmnWaypoint(116.0, 138.0),
        BpmnWaypoint(180.0, 138.0),
    )

    @Test
    fun `validator accepts minimal valid definition`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT, startBounds),
                BpmnNode("Task_1", "Validate request", NodeType.USER_TASK, taskBounds),
                BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT, endBounds),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "Task_1", waypoints = standardWaypoints),
                BpmnEdge(
                    "Flow_2",
                    "Task_1",
                    "EndEvent_1",
                    waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(340.0, 138.0)),
                ),
            ),
        )

        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects missing refs and missing end event`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT, startBounds),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "MissingNode", waypoints = standardWaypoints),
            ),
        )

        val errors = validator.validate(definition)

        assertContains(errors.joinToString("\n"), "targetRef 'MissingNode' does not match any node id")
        assertContains(errors.joinToString("\n"), "definition must contain at least one END_EVENT")
    }
}
