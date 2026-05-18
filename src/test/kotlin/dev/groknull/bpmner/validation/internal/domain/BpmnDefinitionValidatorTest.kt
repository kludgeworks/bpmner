/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class BpmnDefinitionValidatorTest {
    private val validator = BpmnDefinitionValidator()

    @Test
    fun `validator accepts minimal valid definition`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
                        BpmnNode("Task_1", "Validate request", NodeType.USER_TASK),
                        BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                        BpmnEdge("Flow_2", "Task_1", "EndEvent_1"),
                    ),
            )

        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects missing refs and missing end event`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "MissingNode"),
                    ),
            )

        val errors = validator.validate(definition)

        assertContains(errors.joinToString("\n"), "targetRef 'MissingNode' does not match any node id")
        assertContains(errors.joinToString("\n"), "definition must contain at least one END_EVENT")
    }

    @Test
    fun `validator rejects blank task name`() {
        val definition =
            minimalDefinition(
                task = BpmnNode("Task_1", "", NodeType.USER_TASK),
            )

        val errors = validator.validate(definition)

        assertContains(errors.joinToString("\n"), "node Task_1 name must not be blank for USER_TASK")
    }

    @Test
    fun `validator rejects blank event names`() {
        val definition =
            minimalDefinition(
                start = BpmnNode("StartEvent_1", null, NodeType.START_EVENT),
                end = BpmnNode("EndEvent_1", " ", NodeType.END_EVENT),
            )

        val errors = validator.validate(definition).joinToString("\n")

        assertContains(errors, "node StartEvent_1 name must not be blank for START_EVENT")
        assertContains(errors, "node EndEvent_1 name must not be blank for END_EVENT")
    }

    @Test
    fun `validator rejects blank diverging gateway name`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
                        BpmnNode("Gateway_1", null, NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode("Task_1", "Approve request", NodeType.USER_TASK),
                        BpmnNode("Task_2", "Reject request", NodeType.USER_TASK),
                        BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
                        BpmnEdge("Flow_2", "Gateway_1", "Task_1", name = "Approved"),
                        BpmnEdge("Flow_3", "Gateway_1", "Task_2", name = "Rejected"),
                        BpmnEdge("Flow_4", "Task_1", "EndEvent_1"),
                        BpmnEdge("Flow_5", "Task_2", "EndEvent_1"),
                    ),
            )

        val errors = validator.validate(definition)

        assertContains(errors.joinToString("\n"), "node Gateway_1 name must not be blank for EXCLUSIVE_GATEWAY")
    }

    @Test
    fun `validator accepts unnamed converging gateway`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
                        BpmnNode("Task_1", "Approve request", NodeType.USER_TASK),
                        BpmnNode("Task_2", "Reject request", NodeType.USER_TASK),
                        BpmnNode("Gateway_1", null, NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                        BpmnEdge("Flow_2", "StartEvent_1", "Task_2"),
                        BpmnEdge("Flow_3", "Task_1", "Gateway_1"),
                        BpmnEdge("Flow_4", "Task_2", "Gateway_1"),
                        BpmnEdge("Flow_5", "Gateway_1", "EndEvent_1"),
                    ),
            )

        val errors = validator.validate(definition)

        assertTrue(errors.isEmpty(), "Expected unnamed converging gateway to pass, got: $errors")
    }

    private fun minimalDefinition(
        start: BpmnNode = BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT),
        task: BpmnNode = BpmnNode("Task_1", "Validate request", NodeType.USER_TASK),
        end: BpmnNode = BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT),
    ) = BpmnDefinition(
        processId = "Process_1",
        processName = "Handle request",
        nodes = listOf(start, task, end),
        sequences =
            listOf(
                BpmnEdge("Flow_1", start.id, task.id),
                BpmnEdge("Flow_2", task.id, end.id),
            ),
    )
}
