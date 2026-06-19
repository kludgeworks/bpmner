/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnEdge
import dev.groknull.bpmner.domain.BpmnEndEvent
import dev.groknull.bpmner.domain.BpmnMessageEventDefinition
import dev.groknull.bpmner.domain.BpmnMessageRef
import dev.groknull.bpmner.domain.BpmnStartEvent
import dev.groknull.bpmner.domain.BpmnSubProcess
import dev.groknull.bpmner.domain.BpmnUserTask
import org.xmlunit.assertj.XmlAssert
import org.xmlunit.assertj.XmlAssert.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An event subprocess (`triggeredByEvent = true`) round-trips: it carries no connecting sequence
 * flow, its typed inner start (a message trigger) survives, and the interrupting / non-interrupting
 * flag on that start is preserved through render → parse. The non-interrupting case proves the
 * `isInterrupting="false"` attribute round-trips on an event-subprocess start.
 */
class BpmnEventSubProcessRoundTripTest {
    private val converter = BpmnDefinitionToXmlConverter()

    companion object {
        private val NAMESPACES = mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
            "bpmner" to "https://groknull.dev/bpmner/ext",
            "camunda" to "http://camunda.org/schema/1.0/bpmn",
        )
    }

    private fun assertXml(xml: String): XmlAssert {
        return assertThat(xml).withNamespaceContext(NAMESPACES)
    }

    @Test
    fun `interrupting event subprocess round-trips its trigger and flag`() {
        assertEventSubProcessRoundTrips(interrupting = true)
    }

    @Test
    fun `non-interrupting event subprocess round-trips its trigger and flag`() {
        assertEventSubProcessRoundTrips(interrupting = false)
    }

    private fun assertEventSubProcessRoundTrips(interrupting: Boolean) {
        val original = eventSubProcessDefinition(interrupting)

        val xml = converter.render(original).xml
        assertXml(xml).valueByXPath("//bpmn:subProcess[@id='EventSub_1']/@triggeredByEvent").isEqualTo("true")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)

        val sp = parsed.nodes.single { it.id == "EventSub_1" }
        assertIs<BpmnSubProcess>(sp)
        assertTrue(sp.triggeredByEvent, "the event-subprocess marker must keep triggeredByEvent=true")

        // The marker has no connecting flow — the trigger is the inner start, not the parent flow.
        assertTrue(
            parsed.sequences.none { it.sourceRef == "EventSub_1" || it.targetRef == "EventSub_1" },
            "an event subprocess must round-trip with no connecting sequence flow",
        )

        val innerStart = parsed.nodes.single { it.id == "Start_cancel" }
        assertIs<BpmnStartEvent>(innerStart)
        assertEquals("EventSub_1", innerStart.parentRef)
        assertEquals(interrupting, innerStart.isInterrupting, "the interrupting flag must round-trip")
        val trigger = innerStart.eventDefinition
        assertIs<BpmnMessageEventDefinition>(trigger)
        assertEquals("Msg_cancel", trigger.messageRef)
        assertTrue(
            parsed.messages.any { it.id == "Msg_cancel" },
            "the message catalog entry the trigger references must survive the round-trip",
        )
    }

    private fun eventSubProcessDefinition(interrupting: Boolean): BpmnDefinition = BpmnDefinition(
        processId = "Process_order",
        processName = "Custom furniture order",
        nodes =
        listOf(
            // Main flow.
            BpmnStartEvent("Start_main", "Order accepted"),
            BpmnUserTask("act-build", "Build the piece"),
            BpmnEndEvent("End_main", "Delivered"),
            // Event subprocess marker — no connecting edges.
            BpmnSubProcess("EventSub_1", "Handle cancellation", triggeredByEvent = true),
            // Inner flow of the event subprocess: typed message start → handler → end.
            BpmnStartEvent(
                "Start_cancel",
                "Cancellation requested",
                eventDefinition = BpmnMessageEventDefinition(messageRef = "Msg_cancel"),
                isInterrupting = interrupting,
                parentRef = "EventSub_1",
            ),
            BpmnUserTask("act-refund", "Process partial refund", parentRef = "EventSub_1"),
            BpmnEndEvent("End_cancel", "Cancellation handled", parentRef = "EventSub_1"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_m1", "Start_main", "act-build"),
            BpmnEdge("Flow_m2", "act-build", "End_main"),
            BpmnEdge("Flow_c1", "Start_cancel", "act-refund", parentRef = "EventSub_1"),
            BpmnEdge("Flow_c2", "act-refund", "End_cancel", parentRef = "EventSub_1"),
        ),
        messages = listOf(BpmnMessageRef(id = "Msg_cancel", name = "Cancellation requested")),
    )
}
