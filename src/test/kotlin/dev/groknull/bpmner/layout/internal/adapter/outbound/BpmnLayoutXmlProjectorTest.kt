/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test

class BpmnLayoutXmlProjectorTest {

    private val projector = BpmnLayoutXmlProjector()

    @Test
    fun `strips data artifacts and preserves valid XML`() {
        val originalXml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="def_1">
            <process id="proc_1">
                <dataObject id="do_1" />
                <dataObjectReference id="dor_1" dataObjectRef="do_1" />
                <dataStore id="ds_1" />
                <dataStoreReference id="dsr_1" dataStoreRef="ds_1" />
                <userTask id="task_1">
                    <dataOutputAssociation id="doa_1">
                        <targetRef>dor_1</targetRef>
                    </dataOutputAssociation>
                    <dataInputAssociation id="dia_1">
                        <sourceRef>dsr_1</sourceRef>
                    </dataInputAssociation>
                </userTask>
                <association id="assoc_1" sourceRef="task_1" targetRef="dor_1" />
                <association id="assoc_keep" sourceRef="task_1" targetRef="task_2" />
                <userTask id="task_2" />
            </process>
        </definitions>"""

        val projected = projector.projectForLayout(originalXml)

        assertXml(projected).doesNotHaveXPath("//bpmn:dataObject")
        assertXml(projected).doesNotHaveXPath("//bpmn:dataObjectReference")
        assertXml(projected).doesNotHaveXPath("//bpmn:dataStore")
        assertXml(projected).doesNotHaveXPath("//bpmn:dataStoreReference")
        assertXml(projected).doesNotHaveXPath("//bpmn:dataOutputAssociation")
        assertXml(projected).doesNotHaveXPath("//bpmn:dataInputAssociation")
        assertXml(projected).doesNotHaveXPath("//bpmn:association[@id='assoc_1']")

        assertXml(projected).nodesByXPath("//bpmn:association[@id='assoc_keep']").exist()
        assertXml(projected).nodesByXPath("//bpmn:userTask[@id='task_1']").exist()
        assertXml(projected).nodesByXPath("//bpmn:userTask[@id='task_2']").exist()
    }

    @Test
    fun `merges layout diagram correctly`() {
        val originalXml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="def_1">
            <process id="proc_1">
                <userTask id="task_1" />
            </process>
        </definitions>"""

        val layoutedXml = """<?xml version="1.0" encoding="UTF-8" standalone="no"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" id="def_1">
            <process id="proc_1">
                <userTask id="task_1" />
            </process>
            <bpmndi:BPMNDiagram id="diag_1">
                <bpmndi:BPMNPlane id="plane_1" bpmnElement="proc_1" />
            </bpmndi:BPMNDiagram>
        </definitions>"""

        val merged = projector.mergeLayout(originalXml, layoutedXml)

        assertXml(merged).nodesByXPath("//bpmndi:BPMNDiagram").exist()
        assertXml(merged).nodesByXPath("//bpmndi:BPMNPlane").exist()
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
}
