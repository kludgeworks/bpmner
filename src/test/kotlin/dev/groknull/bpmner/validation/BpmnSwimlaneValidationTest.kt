/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnLane
import dev.groknull.bpmner.core.BpmnMessageFlow
import dev.groknull.bpmner.core.BpmnParticipant
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Structural / referential-integrity checks for the collaboration data plane:
 * `BpmnDefinitionValidator.validateSwimlanes`. Cross-pool *semantics* are owned by the
 * MessageFlowAcrossPools Pkl rule; these tests cover the structural guards only.
 */
class BpmnSwimlaneValidationTest {
    private val validator = BpmnDefinitionValidator()

    @Test
    fun `validator accepts a well-formed lane and participant partition`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_self", "Order service", processRef = "Process_1")),
                lanes =
                listOf(
                    BpmnLane("Lane_a", "Sales", "Participant_self", listOf("StartEvent_1", "Task_1")),
                    BpmnLane("Lane_b", "Fulfilment", "Participant_self", listOf("EndEvent_1")),
                ),
            )

        assertTrue(validator.validate(definition).isEmpty(), "got: ${validator.validate(definition)}")
    }

    @Test
    fun `validator rejects a lane flowNodeRef that matches no node`() {
        val definition = swimlaneDefinition(lanes = listOf(BpmnLane("Lane_a", "Sales", null, listOf("Task_1", "Ghost"))))

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "lane Lane_a flowNodeRef 'Ghost' does not match any node id",
        )
    }

    @Test
    fun `validator rejects a node assigned to more than one lane`() {
        val definition =
            swimlaneDefinition(
                lanes =
                listOf(
                    BpmnLane("Lane_a", "Sales", null, listOf("Task_1")),
                    BpmnLane("Lane_b", "Ops", null, listOf("Task_1")),
                ),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "node 'Task_1' is assigned to more than one lane",
        )
    }

    @Test
    fun `validator rejects a lane participantId that matches no participant`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_self", "Self", processRef = "Process_1")),
                lanes = listOf(BpmnLane("Lane_a", "Sales", "Participant_ghost", listOf("Task_1"))),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "lane Lane_a participantId 'Participant_ghost' does not match any participant id",
        )
    }

    @Test
    fun `validator rejects a participant processRef that does not match the process`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_self", "Self", processRef = "Process_other")),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "participant Participant_self processRef 'Process_other' does not match the process id 'Process_1'",
        )
    }

    @Test
    fun `validator rejects a message flow with a dangling endpoint`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_ext", "Carrier", processRef = null)),
                messageFlows = listOf(BpmnMessageFlow("MsgFlow_1", "Ship", "Task_1", "Ghost")),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "message flow MsgFlow_1 targetRef 'Ghost' matches no node or participant id",
        )
    }

    @Test
    fun `validator rejects duplicate message flow ids`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_ext", "Carrier", processRef = null)),
                messageFlows =
                listOf(
                    BpmnMessageFlow("MsgFlow_dup", "Ship", "Task_1", "Participant_ext"),
                    BpmnMessageFlow("MsgFlow_dup", "Confirm", "Participant_ext", "Task_1"),
                ),
            )

        assertContains(
            validator.validate(definition).joinToString("\n"),
            "duplicate message flow id: MsgFlow_dup",
        )
    }

    @Test
    fun `validator accepts a message flow to a black-box participant`() {
        val definition =
            swimlaneDefinition(
                participants = listOf(BpmnParticipant("Participant_ext", "Carrier", processRef = null)),
                messageFlows = listOf(BpmnMessageFlow("MsgFlow_1", "Ship", "Task_1", "Participant_ext")),
            )

        assertTrue(validator.validate(definition).isEmpty(), "got: ${validator.validate(definition)}")
    }

    private fun swimlaneDefinition(
        participants: List<BpmnParticipant> = emptyList(),
        lanes: List<BpmnLane> = emptyList(),
        messageFlows: List<BpmnMessageFlow> = emptyList(),
    ) = BpmnDefinition(
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
        participants = participants,
        lanes = lanes,
        messageFlows = messageFlows,
    )
}
