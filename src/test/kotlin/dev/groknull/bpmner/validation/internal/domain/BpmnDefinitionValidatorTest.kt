/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnTimerKind
import dev.groknull.bpmner.core.BpmnUserTask
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
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnUserTask("Task_1", "Validate request"),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
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
                        BpmnStartEvent("StartEvent_1", "Request received"),
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
                task = BpmnUserTask("Task_1", ""),
            )

        val errors = validator.validate(definition)

        assertContains(errors.joinToString("\n"), "node Task_1 name must not be blank for USER_TASK")
    }

    @Test
    fun `validator rejects blank event names`() {
        val definition =
            minimalDefinition(
                start = BpmnStartEvent("StartEvent_1", null),
                end = BpmnEndEvent("EndEvent_1", " "),
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
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnExclusiveGateway("Gateway_1", null),
                        BpmnUserTask("Task_1", "Approve request"),
                        BpmnUserTask("Task_2", "Reject request"),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
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
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnUserTask("Task_1", "Approve request"),
                        BpmnUserTask("Task_2", "Reject request"),
                        BpmnExclusiveGateway("Gateway_1", null),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
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

    @Test
    fun `validator rejects invalid event definition references and timer expressions`() {
        val danglingMessage =
            minimalDefinition(
                start =
                    BpmnStartEvent(
                        "StartEvent_1",
                        "Request received",
                        eventDefinition = BpmnMessageEventDefinition("Message_missing"),
                    ),
            )
        val blankTimer =
            minimalDefinition(
                start =
                    BpmnStartEvent(
                        "StartEvent_1",
                        "Scheduled start",
                        eventDefinition = BpmnTimerEventDefinition(BpmnTimerKind.DATE, " "),
                    ),
            )

        assertContains(
            validator.validate(danglingMessage).joinToString("\n"),
            "messageRef 'Message_missing' does not match any message catalog id",
        )
        assertContains(
            validator.validate(blankTimer).joinToString("\n"),
            "timer definition expression must not be blank",
        )
    }

    private fun minimalDefinition(
        start: BpmnNode = BpmnStartEvent("StartEvent_1", "Request received"),
        task: BpmnNode = BpmnUserTask("Task_1", "Validate request"),
        end: BpmnNode = BpmnEndEvent("EndEvent_1", "Request completed"),
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
