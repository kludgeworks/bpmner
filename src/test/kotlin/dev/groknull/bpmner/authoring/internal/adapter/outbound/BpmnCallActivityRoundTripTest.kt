/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedNode
import org.xmlunit.assertj.XmlAssert.assertThat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A call activity survives render → parse: it emits `<bpmn:callActivity calledElement="…">` and
 * parses back as a typed [BpmnCallActivity], with its `calledElement` preserved.
 */
class BpmnCallActivityRoundTripTest {
    private val converter = BpmnDefinitionToXmlConverter()

    private companion object {
        private val NAMESPACES = mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
        )
    }

    @Test
    fun `call activity renders calledElement and round-trips as a typed node`() {
        val original =
            BpmnDefinition(
                processId = "web-order-fulfilment",
                processName = "Web order fulfilment",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Order received"),
                    BpmnCallActivity("act-fulfil", "Fulfil order", calledElement = "fulfil-order"),
                    BpmnEndEvent("EndEvent_1", "Shipped"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "act-fulfil"),
                    BpmnEdge("Flow_2", "act-fulfil", "EndEvent_1"),
                ),
            )

        val xml = converter.render(original).xml
        assertThat(xml)
            .withNamespaceContext(NAMESPACES)
            .nodesByXPath("//bpmn:callActivity[@id='act-fulfil'][@calledElement='fulfil-order']")
            .exist()

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)
        val node = parsed.nodes.single { it.id == "act-fulfil" }
        assertIs<BpmnCallActivity>(node)
        assertEquals("fulfil-order", node.calledElement)
        assertEquals("Fulfil order", node.name)
    }

    @Test
    fun `a call activity with no calledElement parses as unrecognized, not a blank typed node`() {
        val original =
            BpmnDefinition(
                processId = "web-order-fulfilment",
                processName = "Web order fulfilment",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Order received"),
                    BpmnCallActivity("act-fulfil", "Fulfil order", calledElement = "fulfil-order"),
                    BpmnEndEvent("EndEvent_1", "Shipped"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "act-fulfil"),
                    BpmnEdge("Flow_2", "act-fulfil", "EndEvent_1"),
                ),
            )

        // Strip calledElement to simulate a malformed call activity: the parser must not fabricate
        // a blank-target typed node (which would render as <callActivity calledElement="">); it
        // surfaces it as unrecognized so the BpmnSubset rule can flag it.
        val xml = converter.render(original).xml.replace(Regex(" calledElement=\"[^\"]*\""), "")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)
        val node = parsed.nodes.single { it.id == "act-fulfil" }
        assertIs<BpmnUnrecognizedNode>(node)
    }
}
