package dev.groknull.bpmner.agent

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.generation.internal.BpmnDefinitionToXmlConverter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BpmnDefinitionToXmlConverterTest {

    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `converter maps nodes sequence flows and bpmndi to bpmn xml`() {
        val definition = BpmnDefinition(
            processId = "Process_42",
            processName = "Prepare toast",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
                BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK, BpmnBounds(180.0, 98.0, 100.0, 80.0)),
                BpmnNode("Gateway_1", "Bread toasted?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(340.0, 110.0, 50.0, 50.0)),
                BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT, BpmnBounds(460.0, 120.0, 36.0, 36.0)),
            ),
            sequences = listOf(
                BpmnEdge(
                    "Flow_1",
                    "StartEvent_1",
                    "Task_1",
                    waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 138.0)),
                ),
                BpmnEdge(
                    "Flow_2",
                    "Task_1",
                    "Gateway_1",
                    waypoints = listOf(BpmnWaypoint(280.0, 138.0), BpmnWaypoint(340.0, 138.0)),
                ),
                BpmnEdge(
                    "Flow_3",
                    "Gateway_1",
                    "EndEvent_1",
                    name = "Yes",
                    conditionExpression = "toastReady",
                    waypoints = listOf(BpmnWaypoint(390.0, 138.0), BpmnWaypoint(460.0, 138.0)),
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
        assertContains(xml, "<bpmndi:BPMNDiagram")
        assertContains(xml, "<dc:Bounds")
        assertContains(xml, "<di:waypoint")
        assertEquals("Process_42", rendered.elementIndex.processId)
        assertEquals("nodes[id=Gateway_1]", rendered.elementIndex.objectRefForElementId("Gateway_1"))
        assertEquals("nodes[id=Gateway_1]", rendered.elementIndex.objectRefForElementId("Gateway_1_di"))
        assertEquals("sequences[id=Flow_3]", rendered.elementIndex.objectRefForElementId("Flow_3"))
        assertEquals("sequences[id=Flow_3]", rendered.elementIndex.objectRefForElementId("Flow_3_di"))
    }

    @Test
    fun `generated xml contains exactly one BPMNDiagram element`() {
        val definition = BpmnDefinition(
            processId = "test_process",
            processName = "Test Process",
            nodes = listOf(
                BpmnNode("start", "Start", NodeType.START_EVENT, BpmnBounds(0.0, 0.0, 36.0, 36.0))
            ),
            sequences = emptyList()
        )

        val xml = converter.toXml(definition)
        val diagramTagCount = xml.split("<bpmndi:BPMNDiagram").size - 1
        assertEquals(1, diagramTagCount, "XML should contain exactly one <bpmndi:BPMNDiagram> tag")
    }

    @Test
    fun `converter omits name attribute for unnamed converging gateway`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Merge decisions",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Request received", NodeType.START_EVENT, BpmnBounds(80.0, 120.0, 36.0, 36.0)),
                BpmnNode("Task_1", "Approve request", NodeType.USER_TASK, BpmnBounds(180.0, 80.0, 100.0, 80.0)),
                BpmnNode("Task_2", "Reject request", NodeType.USER_TASK, BpmnBounds(180.0, 200.0, 100.0, 80.0)),
                BpmnNode("Gateway_1", null, NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(340.0, 140.0, 50.0, 50.0)),
                BpmnNode("EndEvent_1", "Request completed", NodeType.END_EVENT, BpmnBounds(460.0, 147.0, 36.0, 36.0)),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "Task_1", waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 120.0))),
                BpmnEdge("Flow_2", "StartEvent_1", "Task_2", waypoints = listOf(BpmnWaypoint(116.0, 138.0), BpmnWaypoint(180.0, 240.0))),
                BpmnEdge("Flow_3", "Task_1", "Gateway_1", waypoints = listOf(BpmnWaypoint(280.0, 120.0), BpmnWaypoint(340.0, 165.0))),
                BpmnEdge("Flow_4", "Task_2", "Gateway_1", waypoints = listOf(BpmnWaypoint(280.0, 240.0), BpmnWaypoint(340.0, 165.0))),
                BpmnEdge("Flow_5", "Gateway_1", "EndEvent_1", waypoints = listOf(BpmnWaypoint(390.0, 165.0), BpmnWaypoint(460.0, 165.0))),
            ),
        )

        val xml = converter.toXml(definition)

        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertFalse(xml.contains("<exclusiveGateway id=\"Gateway_1\" name="))
        assertContains(xml, "<userTask id=\"Task_1\" name=\"Approve request\"")
    }
}
