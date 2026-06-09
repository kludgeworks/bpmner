/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        assertFalse(projected.contains("dataObject"), "Should remove dataObject")
        assertFalse(projected.contains("dataObjectReference"), "Should remove dataObjectReference")
        assertFalse(projected.contains("dataStore"), "Should remove dataStore")
        assertFalse(projected.contains("dataStoreReference"), "Should remove dataStoreReference")
        assertFalse(projected.contains("dataOutputAssociation"), "Should remove dataOutputAssociation")
        assertFalse(projected.contains("dataInputAssociation"), "Should remove dataInputAssociation")
        assertFalse(projected.contains("assoc_1"), "Should remove association connected to removed node")

        assertTrue(projected.contains("assoc_keep"), "Should keep normal association")
        assertTrue(projected.contains("task_1"), "Should keep task 1")
        assertTrue(projected.contains("task_2"), "Should keep task 2")
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

        assertTrue(merged.contains("xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\""), "Should have bpmndi namespace")
        assertTrue(merged.contains("xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\""), "Should have dc namespace")
        assertTrue(merged.contains("xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\""), "Should have di namespace")
        assertTrue(merged.contains("BPMNDiagram"), "Should contain BPMNDiagram")
        assertTrue(merged.contains("BPMNPlane"), "Should contain BPMNPlane")
    }
}
