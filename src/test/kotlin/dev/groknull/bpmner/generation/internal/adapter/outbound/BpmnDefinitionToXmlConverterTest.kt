/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BpmnDefinitionToXmlConverterTest {
    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `converter maps nodes sequence flows and bpmndi to bpmn xml`() {
        val definition =
            BpmnDefinition(
                processId = "Process_42",
                processName = "Prepare toast",
                nodes =
                    listOf(
                        BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT),
                        BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK),
                        BpmnNode("Gateway_1", "Bread toasted?", NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(
                            "Flow_1",
                            "StartEvent_1",
                            "Task_1",
                        ),
                        BpmnEdge(
                            "Flow_2",
                            "Task_1",
                            "Gateway_1",
                        ),
                        BpmnEdge(
                            "Flow_3",
                            "Gateway_1",
                            "EndEvent_1",
                            name = "Yes",
                            conditionExpression = "toastReady",
                        ),
                    ),
            )

        val rendered = converter.render(definition)
        val xml = rendered.xml

        assertContains(xml, "<process")
        assertContains(xml, "<startEvent id=\"StartEvent_1\"")
        assertContains(xml, "<serviceTask id=\"Task_1\"")
        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertContains(xml, "<endEvent id=\"EndEvent_1\"")
        assertContains(xml, "<sequenceFlow id=\"Flow_3\" name=\"Yes\"")
        assertContains(xml, "<conditionExpression")
        assertContains(xml, "toastReady")
        assertEquals("Process_42", rendered.elementIndex.processId)
        assertEquals("nodes[id=Gateway_1]", rendered.elementIndex.objectRefForElementId("Gateway_1"))
        assertEquals("sequences[id=Flow_3]", rendered.elementIndex.objectRefForElementId("Flow_3"))
    }

    @Test
    fun `generated xml emits no BPMNDI elements or namespaces`() {
        val definition =
            BpmnDefinition(
                processId = "test_process",
                processName = "Test Process",
                nodes =
                    listOf(
                        BpmnNode("start", "Start", NodeType.START_EVENT),
                    ),
                sequences = emptyList(),
            )

        val xml = converter.toXml(definition)
        assertFalse(xml.contains("bpmndi:"), "output must not contain bpmndi: elements or namespace")
        assertFalse(xml.contains("BPMNDiagram"), "output must not contain BPMNDiagram element")
        assertFalse(xml.contains("dc:Bounds"), "output must not contain dc:Bounds element")
        assertFalse(xml.contains("di:waypoint"), "output must not contain di:waypoint element")
    }

    @Test
    fun `converter omits name attribute for unnamed converging gateway`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Merge decisions",
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
                        BpmnEdge(
                            "Flow_1",
                            "StartEvent_1",
                            "Task_1",
                        ),
                        BpmnEdge(
                            "Flow_2",
                            "StartEvent_1",
                            "Task_2",
                        ),
                        BpmnEdge(
                            "Flow_3",
                            "Task_1",
                            "Gateway_1",
                        ),
                        BpmnEdge(
                            "Flow_4",
                            "Task_2",
                            "Gateway_1",
                        ),
                        BpmnEdge(
                            "Flow_5",
                            "Gateway_1",
                            "EndEvent_1",
                        ),
                    ),
            )

        val xml = converter.toXml(definition)

        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertFalse(xml.contains("<exclusiveGateway id=\"Gateway_1\" name="))
        assertContains(xml, "<userTask id=\"Task_1\" name=\"Approve request\"")
    }
}
