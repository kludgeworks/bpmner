/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnAssociation
import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnGroup
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnLane
import dev.groknull.bpmner.bpmn.BpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnParticipant
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnScriptTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnServiceTask
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatBpmnDefinitionMapperTest {
    @Test
    fun `every kindless task FlatBpmnNode round-trips to its sealed equivalent`() {
        assertEquals(BpmnUserTask("u1", "User"), flatNode(FlatBpmnNodeKind.USER_TASK, "u1", "User").toSealed())
        assertEquals(BpmnServiceTask("s1", "Svc"), flatNode(FlatBpmnNodeKind.SERVICE_TASK, "s1", "Svc").toSealed())
        assertEquals(BpmnScriptTask("sc1", "Scr"), flatNode(FlatBpmnNodeKind.SCRIPT_TASK, "sc1", "Scr").toSealed())
        assertEquals(BpmnManualTask("m1", "Manual"), flatNode(FlatBpmnNodeKind.MANUAL_TASK, "m1", "Manual").toSealed())
        assertEquals(
            BpmnExclusiveGateway("g1", "Gate"),
            flatNode(FlatBpmnNodeKind.EXCLUSIVE_GATEWAY, "g1", "Gate").toSealed(),
        )
        assertEquals(
            BpmnInclusiveGateway("i1", "Incl"),
            flatNode(FlatBpmnNodeKind.INCLUSIVE_GATEWAY, "i1", "Incl").toSealed(),
        )
        assertEquals(
            BpmnParallelGateway("p1", "Fork"),
            flatNode(FlatBpmnNodeKind.PARALLEL_GATEWAY, "p1", "Fork").toSealed(),
        )
    }

    @Test
    fun `task multiInstance and definition annotations associations map to sealed`() {
        val flatTask = FlatBpmnNode(
            id = "u1",
            type = FlatBpmnNodeKind.USER_TASK,
            name = "Review",
            multiInstance = FlatMultiInstanceLoopCharacteristics(
                mode = MultiInstanceMode.PARALLEL,
                collectionDescription = "each reviewer",
            ),
        )

        assertEquals(
            BpmnUserTask(
                "u1",
                "Review",
                multiInstance = MultiInstanceLoopCharacteristics(MultiInstanceMode.PARALLEL, "each reviewer"),
            ),
            flatTask.toSealed(),
        )

        val sealed = FlatBpmnDefinition(
            processId = "P",
            processName = "P",
            nodes = listOf(flatTask, FlatBpmnNode(id = "e", type = FlatBpmnNodeKind.END_EVENT, name = "End")),
            sequences = listOf(BpmnEdge("f", "u1", "e")),
            annotations = listOf(BpmnTextAnnotation("ta", "For each reviewer")),
            groups = listOf(BpmnGroup("Group_review", "Review work")),
            associations = listOf(BpmnAssociation("as", "u1", "ta")),
        ).toSealed()

        assertEquals(listOf(BpmnTextAnnotation("ta", "For each reviewer")), sealed.annotations)
        assertEquals(listOf(BpmnGroup("Group_review", "Review work")), sealed.groups)
        assertEquals(listOf(BpmnAssociation("as", "u1", "ta")), sealed.associations)
    }

    @Test
    fun `collaboration participants lanes and message flows map to sealed`() {
        val participants = listOf(
            BpmnParticipant("P_main", "Process", processRef = "P"),
            BpmnParticipant("P_ext", "Payment Provider", processRef = null),
        )
        val lanes = listOf(BpmnLane("Lane_sales", "Sales", participantId = "P_main", flowNodeRefs = listOf("u1")))
        val messageFlows = listOf(BpmnMessageFlow("mf", "Payment request", "u1", "P_ext"))

        val sealed = FlatBpmnDefinition(
            processId = "P",
            processName = "Process",
            nodes = listOf(
                FlatBpmnNode(id = "u1", type = FlatBpmnNodeKind.USER_TASK, name = "Place order"),
                FlatBpmnNode(id = "e", type = FlatBpmnNodeKind.END_EVENT, name = "End"),
            ),
            sequences = listOf(BpmnEdge("f", "u1", "e")),
            participants = participants,
            lanes = lanes,
            messageFlows = messageFlows,
        ).toSealed()

        assertEquals(participants, sealed.participants)
        assertEquals(lanes, sealed.lanes)
        assertEquals(messageFlows, sealed.messageFlows)
    }

    @Test
    fun `BUSINESS_RULE_TASK round-trips with decisionRef`() {
        val flat = FlatBpmnNode(
            id = "br1",
            type = FlatBpmnNodeKind.BUSINESS_RULE_TASK,
            name = "Eval credit",
            decisionRef = "credit-policy",
        )
        assertEquals(
            BpmnBusinessRuleTask(id = "br1", name = "Eval credit", decisionRef = "credit-policy"),
            flat.toSealed(),
        )
    }

    @Test
    fun `SEND_TASK and RECEIVE_TASK round-trip with messageRef`() {
        val send = FlatBpmnNode(
            id = "snd1",
            type = FlatBpmnNodeKind.SEND_TASK,
            name = "Notify",
            messageRef = "msg-confirm",
        )
        assertEquals(
            BpmnSendTask(id = "snd1", name = "Notify", messageRef = "msg-confirm"),
            send.toSealed(),
        )

        val receive = FlatBpmnNode(
            id = "rcv1",
            type = FlatBpmnNodeKind.RECEIVE_TASK,
            name = "Await",
            messageRef = "msg-ack",
        )
        assertEquals(
            BpmnReceiveTask(id = "rcv1", name = "Await", messageRef = "msg-ack"),
            receive.toSealed(),
        )
    }

    @Test
    fun `CALL_ACTIVITY round-trips with calledElement`() {
        val flat = FlatBpmnNode(
            id = "call1",
            type = FlatBpmnNodeKind.CALL_ACTIVITY,
            name = "Fulfil order",
            calledElement = "fulfil-order",
        )
        assertEquals(
            BpmnCallActivity(id = "call1", name = "Fulfil order", calledElement = "fulfil-order"),
            flat.toSealed(),
        )
    }

    @Test
    fun `START_EVENT defaults to NONE event-definition and isInterrupting=true`() {
        val flat = FlatBpmnNode(id = "start1", type = FlatBpmnNodeKind.START_EVENT, name = "Start")
        assertEquals(
            BpmnStartEvent(
                id = "start1",
                name = "Start",
                eventDefinition = BpmnNoneEventDefinition,
                isInterrupting = true,
            ),
            flat.toSealed(),
        )
    }

    @Test
    fun `START_EVENT preserves explicit eventDefinition and isInterrupting=false`() {
        val flat = FlatBpmnNode(
            id = "start2",
            type = FlatBpmnNodeKind.START_EVENT,
            name = "Subprocess start",
            eventDefinition = FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.MESSAGE,
                messageRef = "msg-trigger",
            ),
            isInterrupting = false,
        )
        assertEquals(
            BpmnStartEvent(
                id = "start2",
                name = "Subprocess start",
                eventDefinition = BpmnMessageEventDefinition(messageRef = "msg-trigger"),
                isInterrupting = false,
            ),
            flat.toSealed(),
        )
    }

    @Test
    fun `END_EVENT defaults to NONE event-definition`() {
        val flat = FlatBpmnNode(id = "end1", type = FlatBpmnNodeKind.END_EVENT, name = "Done")
        assertEquals(
            BpmnEndEvent(id = "end1", name = "Done", eventDefinition = BpmnNoneEventDefinition),
            flat.toSealed(),
        )
    }

    @Test
    fun `INTERMEDIATE_CATCH_EVENT requires eventDefinition`() {
        val ok = FlatBpmnNode(
            id = "ic1",
            type = FlatBpmnNodeKind.INTERMEDIATE_CATCH_EVENT,
            name = "Wait for message",
            eventDefinition = FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.MESSAGE,
                messageRef = "msg-go",
            ),
        )
        assertEquals(
            BpmnIntermediateCatchEvent(
                id = "ic1",
                name = "Wait for message",
                eventDefinition = BpmnMessageEventDefinition(messageRef = "msg-go"),
            ),
            ok.toSealed(),
        )

        val missing = FlatBpmnNode(
            id = "ic-bad",
            type = FlatBpmnNodeKind.INTERMEDIATE_CATCH_EVENT,
            name = "x",
            eventDefinition = null,
        )
        val ex = assertFailsWith<IllegalArgumentException> { missing.toSealed() }
        assertTrue("ic-bad" in ex.message.orEmpty())
        assertTrue("eventDefinition" in ex.message.orEmpty())
    }

    @Test
    fun `INTERMEDIATE_THROW_EVENT requires eventDefinition`() {
        val ok = FlatBpmnNode(
            id = "it1",
            type = FlatBpmnNodeKind.INTERMEDIATE_THROW_EVENT,
            name = "Emit",
            eventDefinition = FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.MESSAGE,
                messageRef = "msg-out",
            ),
        )
        assertEquals(
            BpmnIntermediateThrowEvent(
                id = "it1",
                name = "Emit",
                eventDefinition = BpmnMessageEventDefinition(messageRef = "msg-out"),
            ),
            ok.toSealed(),
        )
    }

    @Test
    fun `BOUNDARY_EVENT requires attachedToRef and eventDefinition, defaults cancelActivity=true`() {
        val ok = FlatBpmnNode(
            id = "b1",
            type = FlatBpmnNodeKind.BOUNDARY_EVENT,
            name = "Timeout",
            attachedToRef = "task-1",
            eventDefinition = FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.TIMER,
                timerKind = BpmnTimerKind.DURATION,
                expression = "PT5M",
            ),
        )
        assertEquals(
            BpmnBoundaryEvent(
                id = "b1",
                name = "Timeout",
                attachedToRef = "task-1",
                eventDefinition = BpmnTimerEventDefinition(timerKind = BpmnTimerKind.DURATION, expression = "PT5M"),
            ),
            ok.toSealed(),
        )

        val missingAttach = ok.copy(attachedToRef = null)
        val ex1 = assertFailsWith<IllegalArgumentException> { missingAttach.toSealed() }
        assertTrue("b1" in ex1.message.orEmpty())
        assertTrue("attachedToRef" in ex1.message.orEmpty())

        val missingEvent = ok.copy(eventDefinition = null)
        val ex2 = assertFailsWith<IllegalArgumentException> { missingEvent.toSealed() }
        assertTrue("b1" in ex2.message.orEmpty())
        assertTrue("eventDefinition" in ex2.message.orEmpty())
    }

    @Test
    fun `every FlatBpmnEventDefinitionKind round-trips to the matching sealed subtype`() {
        assertEquals(
            BpmnNoneEventDefinition,
            FlatBpmnEventDefinition(type = FlatBpmnEventDefinitionKind.NONE).toSealed(),
        )
        assertEquals(
            BpmnTerminateEventDefinition,
            FlatBpmnEventDefinition(type = FlatBpmnEventDefinitionKind.TERMINATE).toSealed(),
        )
        assertEquals(
            BpmnTimerEventDefinition(timerKind = BpmnTimerKind.CYCLE, expression = "R/PT1H"),
            FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.TIMER,
                timerKind = BpmnTimerKind.CYCLE,
                expression = "R/PT1H",
            ).toSealed(),
        )
        assertEquals(
            BpmnMessageEventDefinition(messageRef = "msg-x"),
            FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.MESSAGE,
                messageRef = "msg-x",
            ).toSealed(),
        )
        assertEquals(
            BpmnErrorEventDefinition(errorRef = "err-x"),
            FlatBpmnEventDefinition(
                type = FlatBpmnEventDefinitionKind.ERROR,
                errorRef = "err-x",
            ).toSealed(),
        )
    }

    @Test
    fun `BUSINESS_RULE_TASK without decisionRef fails with the offending id`() {
        val flat = FlatBpmnNode(id = "br-bad", type = FlatBpmnNodeKind.BUSINESS_RULE_TASK, name = "x")
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("br-bad" in ex.message.orEmpty())
        assertTrue("decisionRef" in ex.message.orEmpty())
    }

    @Test
    fun `kind-required CharSequence fields reject blank values, not just null`() {
        val flat = FlatBpmnNode(
            id = "br-blank",
            type = FlatBpmnNodeKind.BUSINESS_RULE_TASK,
            name = "x",
            decisionRef = "   ",
        )
        val ex = assertFailsWith<IllegalArgumentException> { flat.toSealed() }
        assertTrue("br-blank" in ex.message.orEmpty())
        assertTrue("decisionRef" in ex.message.orEmpty())
    }

    @Test
    fun `extra fields on the wrong kind are tolerated`() {
        // LLM may emit decisionRef on a USER_TASK; the mapper ignores it, not reject.
        val flat = FlatBpmnNode(
            id = "u-ok",
            type = FlatBpmnNodeKind.USER_TASK,
            name = "Approve",
            decisionRef = "ignored",
            messageRef = "ignored",
            attachedToRef = "ignored",
        )
        assertEquals(BpmnUserTask(id = "u-ok", name = "Approve"), flat.toSealed())
    }

    @Test
    fun `FlatBpmnDefinition toSealed composes per-element mappers and preserves siblings`() {
        val edges = listOf(BpmnEdge(id = "f1", sourceRef = "s1", targetRef = "u1"))
        val messages = listOf(BpmnMessageRef(id = "m1", name = "Confirm"))
        val errors = listOf(BpmnErrorRef(id = "e1", code = "REJECTED"))
        val groups = listOf(BpmnGroup(id = "g1", name = "Visual group"))

        val flat = FlatBpmnDefinition(
            processId = "Process_1",
            processName = "Order intake",
            nodes = listOf(
                flatNode(FlatBpmnNodeKind.START_EVENT, "s1", "Start"),
                flatNode(FlatBpmnNodeKind.USER_TASK, "u1", "Approve"),
                flatNode(FlatBpmnNodeKind.END_EVENT, "e1", "Done"),
            ),
            sequences = edges,
            messages = messages,
            errors = errors,
            groups = groups,
        )

        val sealed: BpmnDefinition = flat.toSealed()
        assertEquals("Process_1", sealed.processId)
        assertEquals(3, sealed.nodes.size)
        assertTrue(sealed.nodes[0] is BpmnStartEvent)
        assertTrue(sealed.nodes[1] is BpmnUserTask)
        assertTrue(sealed.nodes[2] is BpmnEndEvent)
        // Non-sealed siblings reused unchanged.
        assertEquals(edges, sealed.sequences)
        assertEquals(messages, sealed.messages)
        assertEquals(errors, sealed.errors)
        assertEquals(groups, sealed.groups)
    }

    @Test
    fun `SUB_PROCESS maps to BpmnSubProcess and parentRef threads through every nested node`() {
        val flat = FlatBpmnDefinition(
            processId = "P",
            processName = "P",
            nodes =
            listOf(
                FlatBpmnNode(id = "sp", type = FlatBpmnNodeKind.SUB_PROCESS, name = "Handle"),
                FlatBpmnNode(id = "s", type = FlatBpmnNodeKind.START_EVENT, name = "Begin", parentRef = "sp"),
                FlatBpmnNode(id = "u", type = FlatBpmnNodeKind.USER_TASK, name = "Work", parentRef = "sp"),
                FlatBpmnNode(id = "e", type = FlatBpmnNodeKind.END_EVENT, name = "Done", parentRef = "sp"),
            ),
            sequences = listOf(BpmnEdge("f", "s", "u", parentRef = "sp")),
        )

        val sealed = flat.toSealed()

        val sp = sealed.nodes.single { it.id == "sp" }
        assertIs<BpmnSubProcess>(sp)
        assertEquals(
            mapOf("sp" to null, "s" to "sp", "u" to "sp", "e" to "sp"),
            sealed.nodes.associate { it.id to it.parentRef },
        )
        assertEquals("sp", sealed.sequences.single().parentRef)
    }

    // --- Convert-site tests (sites 1-3): defensive else branches in dispatch helpers ---
    // These branches handle unrecognised node kinds produced by LLM output.

    // Site 1: toTaskNode called with non-task kind
    @Test
    fun `toTaskNode with non-task kind throws RetryableBpmnGenerationException`() {
        val node = FlatBpmnNode(id = "ev1", type = FlatBpmnNodeKind.START_EVENT, name = "Start")
        val ex = assertFailsWith<RetryableBpmnGenerationException> { node.toTaskNode() }
        assertContains(ex.message!!, "toTaskNode called with non-task kind")
        assertContains(ex.message!!, "START_EVENT")
    }

    // Site 2: toGatewayNode called with non-gateway kind
    @Test
    fun `toGatewayNode with non-gateway kind throws RetryableBpmnGenerationException`() {
        val node = FlatBpmnNode(id = "t1", type = FlatBpmnNodeKind.USER_TASK, name = "Task")
        val ex = assertFailsWith<RetryableBpmnGenerationException> { node.toGatewayNode() }
        assertContains(ex.message!!, "toGatewayNode called with non-gateway kind")
        assertContains(ex.message!!, "USER_TASK")
    }

    // Site 3: toEventPositionNode called with non-event-position kind
    @Test
    fun `toEventPositionNode with non-event-position kind throws RetryableBpmnGenerationException`() {
        val node = FlatBpmnNode(id = "u1", type = FlatBpmnNodeKind.USER_TASK, name = "Task")
        val ex = assertFailsWith<RetryableBpmnGenerationException> { node.toEventPositionNode() }
        assertContains(ex.message!!, "toEventPositionNode called with non-event-position kind")
        assertContains(ex.message!!, "USER_TASK")
    }

    private fun flatNode(
        type: FlatBpmnNodeKind,
        id: String,
        name: String? = null,
    ): FlatBpmnNode = FlatBpmnNode(id = id, type = type, name = name)
}
