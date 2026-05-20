/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnTimerKind
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
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

    @Test
    fun `validator rejects none event definitions on intermediate events`() {
        val catchDefinition =
            intermediateDefinition(
                BpmnIntermediateCatchEvent(
                    id = "Catch_1",
                    name = "Wait",
                    eventDefinition = BpmnNoneEventDefinition,
                ),
            )
        val throwDefinition =
            intermediateDefinition(
                BpmnIntermediateThrowEvent(
                    id = "Throw_1",
                    name = "Notify",
                    eventDefinition = BpmnNoneEventDefinition,
                ),
            )

        assertContains(
            validator.validate(catchDefinition).joinToString("\n"),
            "intermediate catch event Catch_1 must declare an event definition",
        )
        assertContains(
            validator.validate(throwDefinition).joinToString("\n"),
            "intermediate throw event Throw_1 must declare an event definition",
        )
    }

    @Test
    fun `validator rejects none event definitions on boundary events`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnUserTask("Task_1", "Validate request"),
                        BpmnBoundaryEvent(
                            id = "Boundary_1",
                            name = "Timeout",
                            attachedToRef = "Task_1",
                            eventDefinition = BpmnNoneEventDefinition,
                        ),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                        BpmnEdge("Flow_2", "Task_1", "EndEvent_1"),
                    ),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "boundary event Boundary_1 must declare an event definition",
        )
    }

    // Blank-ref pre-checks: when a `<bpmn:messageEventDefinition/>` (etc.) has no `messageRef`
    // attribute, the parser produces `BpmnMessageEventDefinition("")` so the malformed XML is
    // captured faithfully. The validator must then surface the *missing attribute* — not a
    // referential-integrity error that misleads about the real bug.

    @Test
    fun `validator flags missing messageRef attribute on messageEventDefinition`() {
        val definition =
            minimalDefinition(
                start =
                    BpmnStartEvent(
                        "StartEvent_1",
                        "Request received",
                        eventDefinition = BpmnMessageEventDefinition(""),
                    ),
            )
        assertContains(
            validator.validate(definition).joinToString("\n"),
            "event StartEvent_1 messageEventDefinition is missing the required messageRef attribute",
        )
    }

    @Test
    fun `validator flags missing signalRef attribute on signalEventDefinition`() {
        val definition =
            minimalDefinition(
                start =
                    BpmnStartEvent(
                        "StartEvent_1",
                        "Broadcast caught",
                        eventDefinition = BpmnSignalEventDefinition(""),
                    ),
            )
        assertContains(
            validator.validate(definition).joinToString("\n"),
            "event StartEvent_1 signalEventDefinition is missing the required signalRef attribute",
        )
    }

    @Test
    fun `validator flags missing errorRef attribute on errorEventDefinition`() {
        val definition =
            minimalDefinition(
                end =
                    BpmnEndEvent(
                        "EndEvent_1",
                        "Errored out",
                        eventDefinition = BpmnErrorEventDefinition(""),
                    ),
            )
        assertContains(
            validator.validate(definition).joinToString("\n"),
            "event EndEvent_1 errorEventDefinition is missing the required errorRef attribute",
        )
    }

    @Test
    fun `validator flags missing escalationRef attribute on escalationEventDefinition`() {
        val definition =
            minimalDefinition(
                end =
                    BpmnEndEvent(
                        "EndEvent_1",
                        "Escalated",
                        eventDefinition = BpmnEscalationEventDefinition(""),
                    ),
            )
        assertContains(
            validator.validate(definition).joinToString("\n"),
            "event EndEvent_1 escalationEventDefinition is missing the required escalationRef attribute",
        )
    }

    @Test
    fun `validator flags missing attachedToRef on boundary event`() {
        // Same trap: BoundaryEvent(attachedToRef = "") used to surface as
        // "attachedToRef '' does not match any node id" — a referential-integrity message that
        // confuses with a node-graph bug. Now it correctly reports the missing attribute.
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                    listOf(
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnUserTask("Task_1", "Validate request"),
                        BpmnBoundaryEvent(
                            id = "Boundary_1",
                            name = "Timeout",
                            attachedToRef = "",
                            eventDefinition = BpmnTimerEventDefinition(BpmnTimerKind.DURATION, "PT24H"),
                        ),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                        BpmnEdge("Flow_2", "Task_1", "EndEvent_1"),
                    ),
            )
        assertContains(
            validator.validate(definition).joinToString("\n"),
            "boundary event Boundary_1 is missing the required attachedToRef attribute",
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

    private fun intermediateDefinition(intermediate: BpmnNode) =
        BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Request received"),
                    intermediate,
                    BpmnEndEvent("EndEvent_1", "Request completed"),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", intermediate.id),
                    BpmnEdge("Flow_2", intermediate.id, "EndEvent_1"),
                ),
        )
}
