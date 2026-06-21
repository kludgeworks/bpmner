/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.internal.model.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnManualTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.internal.model.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnScriptTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSendTask
import dev.groknull.bpmner.bpmn.internal.model.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

@Suppress("TooManyFunctions") // test class — each @Test method is one function
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
    fun `validator accepts single default flow on exclusive gateway`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Credit-tier routing",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Score received"),
                    BpmnExclusiveGateway("Gateway_1", "What credit tier?"),
                    BpmnUserTask("Task_fast", "Fast-track underwriting"),
                    BpmnUserTask("Task_standard", "Standard underwriting"),
                    BpmnUserTask("Task_manual", "Manual review"),
                    BpmnEndEvent("EndEvent_1", "Offer generated"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Gateway_1", "Task_fast", conditionExpression = "score >= 750"),
                    BpmnEdge("Flow_3", "Gateway_1", "Task_standard", conditionExpression = "score in 600..749"),
                    BpmnEdge("Flow_4", "Gateway_1", "Task_manual", isDefault = true),
                    BpmnEdge("Flow_5", "Task_fast", "EndEvent_1"),
                    BpmnEdge("Flow_6", "Task_standard", "EndEvent_1"),
                    BpmnEdge("Flow_7", "Task_manual", "EndEvent_1"),
                ),
            )

        val errors = validator.validate(definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `validator rejects multiple default flows on the same gateway`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Handle request",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Request received"),
                    BpmnExclusiveGateway("Gateway_1", "Which path?"),
                    BpmnUserTask("Task_1", "Approve request"),
                    BpmnUserTask("Task_2", "Reject request"),
                    BpmnEndEvent("EndEvent_1", "Request completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
                    BpmnEdge("Flow_2", "Gateway_1", "Task_1", isDefault = true),
                    BpmnEdge("Flow_3", "Gateway_1", "Task_2", isDefault = true),
                    BpmnEdge("Flow_4", "Task_1", "EndEvent_1"),
                    BpmnEdge("Flow_5", "Task_2", "EndEvent_1"),
                ),
            )

        val errors = validator.validate(definition)

        assertContains(
            errors.joinToString("\n"),
            "node Gateway_1 has 2 default flows (Flow_2, Flow_3); at most one is allowed",
        )
    }

    @Test
    fun `validator rejects default flow whose source is not an exclusive gateway`() {
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
                    BpmnEdge("Flow_2", "Task_1", "EndEvent_1", isDefault = true),
                ),
            )

        val errors = validator.validate(definition)

        assertContains(
            errors.joinToString("\n"),
            "edge Flow_2 isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY",
        )
    }

    @Test
    fun `validator rejects orphan default flow whose source resolves to no node`() {
        // An isDefault edge with an unresolved sourceRef must still trigger the
        // "EXCLUSIVE_GATEWAY required" rule. validateEdges separately catches the missing
        // sourceRef, but validateDefaultFlows owns the source-type guarantee and must fire
        // on the orphan case to be complete.
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
                    // sourceRef "Gateway_orphan" doesn't resolve to any node.
                    BpmnEdge("Flow_orphan", "Gateway_orphan", "EndEvent_1", isDefault = true),
                ),
            )

        val errors = validator.validate(definition)
        val joined = errors.joinToString("\n")

        // The dedicated source-type rule fires on the orphan ...
        assertContains(
            joined,
            "edge Flow_orphan isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY",
        )
        // ... alongside the existing missing-sourceRef rule from validateEdges.
        assertContains(
            joined,
            "edge Flow_orphan sourceRef 'Gateway_orphan' does not match any node id",
        )
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
        messages: List<BpmnMessageRef> = emptyList(),
    ) = BpmnDefinition(
        processId = "Process_1",
        processName = "Handle request",
        nodes = listOf(start, task, end),
        sequences =
        listOf(
            BpmnEdge("Flow_1", start.id, task.id),
            BpmnEdge("Flow_2", task.id, end.id),
        ),
        messages = messages,
    )

    @Test
    fun `validator accepts new task subtypes with proper payloads`() {
        val cases =
            listOf(
                BpmnScriptTask("Task_1", "Normalise address"),
                BpmnBusinessRuleTask("Task_1", "Evaluate credit policy", decisionRef = "credit-policy"),
                BpmnManualTask("Task_1", "Inspect property"),
            )
        cases.forEach { task ->
            val errors = validator.validate(minimalDefinition(task = task))
            assertTrue(
                errors.isEmpty(),
                "task ${task::class.simpleName} should validate clean; got: $errors",
            )
        }
    }

    @Test
    fun `validator accepts send and receive tasks with resolvable messageRef`() {
        val cases =
            listOf(
                BpmnSendTask("Task_1", "Send decline notification", messageRef = "Message_Decline"),
                BpmnReceiveTask("Task_1", "Customer acknowledgement received", messageRef = "Message_Decline"),
            )
        cases.forEach { task ->
            val errors =
                validator.validate(
                    minimalDefinition(
                        task = task,
                        messages = listOf(BpmnMessageRef("Message_Decline", "Decline notification")),
                    ),
                )
            assertTrue(
                errors.isEmpty(),
                "task ${task::class.simpleName} with catalogued messageRef should validate clean; got: $errors",
            )
        }
    }

    @Test
    fun `validator rejects send and receive tasks with unresolved messageRef`() {
        // messageRef is non-blank but points at no entry in the message catalogue.
        val sendErrors =
            validator.validate(
                minimalDefinition(task = BpmnSendTask("Task_1", "Send decline", messageRef = "Message_Missing")),
            )
        assertContains(
            sendErrors.joinToString("\n"),
            "sendTask Task_1 messageRef 'Message_Missing' does not match any message catalog id",
        )
        val receiveErrors =
            validator.validate(
                minimalDefinition(task = BpmnReceiveTask("Task_1", "Awaited", messageRef = "Message_Missing")),
            )
        assertContains(
            receiveErrors.joinToString("\n"),
            "receiveTask Task_1 messageRef 'Message_Missing' does not match any message catalog id",
        )
    }

    @Test
    fun `validator rejects business rule task with blank decisionRef`() {
        // Construct via the no-arg secondary path: NotBlank bean-validation is bypassed for the
        // explicit empty-string we feed in, so the validator's own diagnostic stream must fire.
        val errors =
            validator.validate(
                minimalDefinition(task = BpmnBusinessRuleTask("Task_1", "Evaluate", decisionRef = "")),
            )
        assertContains(
            errors.joinToString("\n"),
            "businessRuleTask Task_1 is missing the required decisionRef attribute",
        )
    }

    private fun intermediateDefinition(intermediate: BpmnNode) = BpmnDefinition(
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
