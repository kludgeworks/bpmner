/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnLane
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnParticipant
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pools, lanes, and message flows survive render → parse → render. The renderer emits a
 * `<collaboration>` (participants + message flows) and a `<laneSet>` inside the process; the parser
 * recovers them via DOM scan, binding each lane to the white-box participant whose processRef names
 * the enclosing process. No DI is asserted — the auto-layout step owns swimlane geometry.
 */
class BpmnCollaborationRoundTripTest {
    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `white-box pool with lanes round-trips participant and lane membership`() {
        val original = whiteBoxWithLanes()

        val xml = converter.render(original).xml
        assertContains(xml, "<bpmn:collaboration")
        assertContains(xml, "processRef=\"Process_sales\"")
        assertContains(xml, "<bpmn:laneSet")
        assertContains(xml, "<bpmn:lane id=\"Lane_front\"")
        assertContains(xml, "act-submit</")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)

        val participant = parsed.participants.single()
        assertEquals("Participant_sales", participant.id)
        assertEquals("Sales", participant.name)
        assertEquals("Process_sales", participant.processRef)

        assertEquals(2, parsed.lanes.size)
        val front = parsed.lanes.single { it.id == "Lane_front" }
        assertEquals("Front office", front.name)
        assertEquals("Participant_sales", front.participantId, "lane binds to the pool owning its process")
        assertEquals(listOf("StartEvent_1", "act-submit"), front.flowNodeRefs)
        val back = parsed.lanes.single { it.id == "Lane_back" }
        assertEquals(listOf("act-fulfil", "EndEvent_1"), back.flowNodeRefs)
    }

    @Test
    fun `black-box pool and message flow round-trip`() {
        val original = blackBoxWithMessageFlow()

        val xml = converter.render(original).xml
        assertContains(xml, "<bpmn:participant id=\"Participant_psp\"")
        assertContains(xml, "<bpmn:messageFlow")
        assertContains(xml, "targetRef=\"Participant_psp\"")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)

        assertEquals(2, parsed.participants.size)
        val blackBox = parsed.participants.single { it.id == "Participant_psp" }
        assertEquals("Payment Provider", blackBox.name)
        assertNull(blackBox.processRef, "a black-box participant declares no process")

        val flow = parsed.messageFlows.single()
        assertEquals("MessageFlow_1", flow.id)
        assertEquals("Payment request", flow.name)
        assertEquals("act-place", flow.sourceRef)
        assertEquals("Participant_psp", flow.targetRef)
        // A message flow crosses pools — it never appears as a sequence flow.
        assertEquals(emptyList(), parsed.sequences.filter { it.id == "MessageFlow_1" })
    }

    @Test
    fun `lanes without a pool round-trip with a null participant`() {
        // A process may carry a <laneSet> without a surrounding <collaboration>; such lanes belong to
        // no participant. The renderer emits the laneSet inside the process and the parser recovers a
        // null participantId rather than fabricating a pool.
        val original = whiteBoxWithLanes().copy(participants = emptyList())

        val xml = converter.render(original).xml
        assertContains(xml, "<bpmn:laneSet")
        assertFalse(xml.contains("<bpmn:collaboration"), "no participants means no collaboration is emitted")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)
        assertEquals(emptyList(), parsed.participants)
        assertEquals(2, parsed.lanes.size)
        parsed.lanes.forEach { assertNull(it.participantId, "a lane outside a collaboration binds to no participant") }
        assertEquals(listOf("StartEvent_1", "act-submit"), parsed.lanes.single { it.id == "Lane_front" }.flowNodeRefs)
    }

    private fun whiteBoxWithLanes(): BpmnDefinition = BpmnDefinition(
        processId = "Process_sales",
        processName = "Sales",
        nodes = listOf(
            BpmnStartEvent("StartEvent_1", "Start"),
            BpmnUserTask("act-submit", "Submit order"),
            BpmnUserTask("act-fulfil", "Fulfil order"),
            BpmnEndEvent("EndEvent_1", "Done"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "act-submit"),
            BpmnEdge("Flow_2", "act-submit", "act-fulfil"),
            BpmnEdge("Flow_3", "act-fulfil", "EndEvent_1"),
        ),
        participants = listOf(
            BpmnParticipant("Participant_sales", "Sales", processRef = "Process_sales"),
        ),
        lanes = listOf(
            BpmnLane("Lane_front", "Front office", "Participant_sales", listOf("StartEvent_1", "act-submit")),
            BpmnLane("Lane_back", "Back office", "Participant_sales", listOf("act-fulfil", "EndEvent_1")),
        ),
    )

    private fun blackBoxWithMessageFlow(): BpmnDefinition = BpmnDefinition(
        processId = "Process_order",
        processName = "Order",
        nodes = listOf(
            BpmnStartEvent("StartEvent_1", "Start"),
            BpmnUserTask("act-place", "Place order"),
            BpmnEndEvent("EndEvent_1", "Done"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "act-place"),
            BpmnEdge("Flow_2", "act-place", "EndEvent_1"),
        ),
        participants = listOf(
            BpmnParticipant("Participant_order", "Order Service", processRef = "Process_order"),
            BpmnParticipant("Participant_psp", "Payment Provider"),
        ),
        messageFlows = listOf(
            BpmnMessageFlow("MessageFlow_1", "Payment request", "act-place", "Participant_psp"),
        ),
    )
}
