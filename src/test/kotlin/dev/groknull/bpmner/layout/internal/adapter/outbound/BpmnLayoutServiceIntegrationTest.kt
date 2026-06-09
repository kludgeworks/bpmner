/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class BpmnLayoutServiceIntegrationTest {

    private lateinit var layoutService: BpmnLayoutService

    @BeforeEach
    fun setUp() {
        layoutService = BpmnLayoutService()
        layoutService.init()
    }

    @AfterEach
    fun tearDown() {
        layoutService.destroy()
    }

    @Test
    fun `layouts XML with data artifacts end-to-end successfully`() {
        val xmlWithDataObjects = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:dataObjectReference id="DataObjectReference_1" dataObjectRef="DataObject_1"/>
    <bpmn:dataObject id="DataObject_1"/>
    <bpmn:task id="Task_1">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:dataInputAssociation id="DataInputAssociation_1">
        <bpmn:sourceRef>DataObjectReference_1</bpmn:sourceRef>
      </bpmn:dataInputAssociation>
    </bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
  </bpmn:process>
</bpmn:definitions>"""

        val layoutedXml = layoutService.layout(xmlWithDataObjects)

        assertXml(layoutedXml).nodesByXPath("//bpmndi:BPMNDiagram").exist()
        assertXml(layoutedXml).nodesByXPath("//bpmndi:BPMNPlane").exist()
        assertXml(layoutedXml).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='StartEvent_1']").exist()
        assertXml(layoutedXml).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Task_1']").exist()
        assertXml(layoutedXml).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1']").exist()
        // The data objects were stripped during layout but should remain in the original XML definition
        assertXml(layoutedXml).nodesByXPath("//bpmn:dataObjectReference").exist()
    }

    private fun assertXml(xml: String): org.xmlunit.assertj.XmlAssert {
        return org.xmlunit.assertj.XmlAssert.assertThat(xml)
            .withNamespaceContext(
                mapOf(
                    "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
                    "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
                    "dc" to "http://www.omg.org/spec/DD/20100524/DC",
                    "di" to "http://www.omg.org/spec/DD/20100524/DI",
                ),
            )
    }

    @Test
    fun `throws BpmnAutoLayoutException on malformed XML`() {
        assertFailsWith<BpmnAutoLayoutException> {
            layoutService.layout("not xml")
        }
    }
}
