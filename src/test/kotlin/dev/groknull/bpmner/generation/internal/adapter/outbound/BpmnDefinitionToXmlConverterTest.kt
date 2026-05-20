/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BpmnDefinitionToXmlConverterTest {
    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `converter emits parallelGateway for BpmnParallelGateway nodes`() {
        // Three-track parallel fork mirroring the employee-onboarding sample.
        val definition =
            BpmnDefinition(
                processId = "Process_Parallel",
                processName = "Parallel preparation",
                nodes =
                    listOf(
                        BpmnStartEvent("StartEvent_1", "Hire confirmed"),
                        BpmnParallelGateway("dec-prep-tracks", "Run preparation tracks"),
                        BpmnUserTask("act-prep-it", "Provision IT"),
                        BpmnUserTask("act-prep-facilities", "Prepare workspace"),
                        BpmnUserTask("act-prep-manager", "Manager briefing"),
                        BpmnParallelGateway("Gateway_join_prep", null),
                        BpmnUserTask("act-orientation", "Orientation"),
                        BpmnEndEvent("EndEvent_1", "Onboarding complete"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge("F1", "StartEvent_1", "dec-prep-tracks"),
                        BpmnEdge("F2", "dec-prep-tracks", "act-prep-it"),
                        BpmnEdge("F3", "dec-prep-tracks", "act-prep-facilities"),
                        BpmnEdge("F4", "dec-prep-tracks", "act-prep-manager"),
                        BpmnEdge("F5", "act-prep-it", "Gateway_join_prep"),
                        BpmnEdge("F6", "act-prep-facilities", "Gateway_join_prep"),
                        BpmnEdge("F7", "act-prep-manager", "Gateway_join_prep"),
                        BpmnEdge("F8", "Gateway_join_prep", "act-orientation"),
                        BpmnEdge("F9", "act-orientation", "EndEvent_1"),
                    ),
            )

        val xml = converter.render(definition).xml

        assertContains(xml, "<parallelGateway id=\"dec-prep-tracks\"")
        assertContains(xml, "<parallelGateway id=\"Gateway_join_prep\"")
        // No condition expressions on any of the parallel-branch flows
        assertFalse(xml.contains("<conditionExpression"), "Parallel branches must not carry conditions")
    }

    @Test
    fun `converter maps nodes sequence flows and bpmndi to bpmn xml`() {
        val definition =
            BpmnDefinition(
                processId = "Process_42",
                processName = "Prepare toast",
                nodes =
                    listOf(
                        BpmnStartEvent("StartEvent_1", "Order received"),
                        BpmnServiceTask("Task_1", "Toast bread"),
                        BpmnExclusiveGateway("Gateway_1", "Bread toasted?"),
                        BpmnEndEvent("EndEvent_1", "Toast served"),
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
                        BpmnStartEvent("start", "Start"),
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
                        BpmnStartEvent("StartEvent_1", "Request received"),
                        BpmnUserTask("Task_1", "Approve request"),
                        BpmnUserTask("Task_2", "Reject request"),
                        BpmnExclusiveGateway("Gateway_1", null),
                        BpmnEndEvent("EndEvent_1", "Request completed"),
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
