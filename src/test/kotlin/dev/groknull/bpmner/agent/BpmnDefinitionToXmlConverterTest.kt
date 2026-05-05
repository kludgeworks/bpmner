package dev.groknull.bpmner.agent

import kotlin.test.Test
import kotlin.test.assertContains

class BpmnDefinitionToXmlConverterTest {

    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `converter maps nodes and sequence flows to bpmn xml`() {
        val definition = BpmnDefinition(
            processId = "Process_42",
            processName = "Prepare toast",
            nodes = listOf(
                BpmnNode("StartEvent_1", "Order received", NodeType.START_EVENT),
                BpmnNode("Task_1", "Toast bread", NodeType.SERVICE_TASK),
                BpmnNode("Gateway_1", "Bread toasted?", NodeType.EXCLUSIVE_GATEWAY),
                BpmnNode("EndEvent_1", "Toast served", NodeType.END_EVENT),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                BpmnEdge("Flow_2", "Task_1", "Gateway_1"),
                BpmnEdge("Flow_3", "Gateway_1", "EndEvent_1", "Yes"),
            ),
        )

        val xml = converter.toXml(definition)

        assertContains(xml, "<process")
        assertContains(xml, "<startEvent id=\"StartEvent_1\"")
        assertContains(xml, "<serviceTask id=\"Task_1\"")
        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertContains(xml, "<endEvent id=\"EndEvent_1\"")
        assertContains(xml, "<sequenceFlow id=\"Flow_3\" name=\"Yes\"")
    }
}
