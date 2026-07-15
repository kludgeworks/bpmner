/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Contract tests for the DI-merge gate (§557-4 architecture note).
 *
 * Verifies that [ElkBpmnLayouter] preserves non-geometry DI attributes
 * (bioc: colour extensions) when re-laying-out a model that already has DI.
 * The golden-file gate ([ElkGoldenLayoutTest.DI-merge preserves bioc colour attributes on re-laid-out shapes])
 * is the byte-exact outer ring; this test is the targeted behaviour check.
 */
class DIMergeTest {

    private val layouter = ElkBpmnLayouter()

    @Test
    fun `bioc stroke and fill survive round-trip re-layout`() {
        val inputWithBioc = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  xmlns:bioc="http://bpmn.io/schema/bpmn/biocolor/1.0"
                  id="Def_bioc" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_bioc" isExecutable="true">
    <bpmn:startEvent id="Start_bioc" name="Start">
      <bpmn:outgoing>Flow_bioc</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:endEvent id="End_bioc" name="End">
      <bpmn:incoming>Flow_bioc</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_bioc" sourceRef="Start_bioc" targetRef="End_bioc"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_bioc">
    <bpmndi:BPMNPlane id="BPMNPlane_bioc" bpmnElement="Process_bioc">
      <bpmndi:BPMNShape id="BPMNShape_Start_bioc" bpmnElement="Start_bioc"
                        bioc:stroke="#005b1d" bioc:fill="#b5efcd">
        <dc:Bounds x="182" y="102" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="BPMNShape_End_bioc" bpmnElement="End_bioc"
                        bioc:stroke="#831311" bioc:fill="#ffcdd2">
        <dc:Bounds x="362" y="102" width="36" height="36"/>
      </bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="BPMNEdge_Flow_bioc" bpmnElement="Flow_bioc">
        <di:waypoint x="218" y="120"/>
        <di:waypoint x="362" y="120"/>
      </bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""

        val result = layouter.layout(inputWithBioc)

        assertTrue(result.contains("bioc:stroke"), "bioc:stroke must be present in re-laid-out output")
        assertTrue(result.contains("bioc:fill"), "bioc:fill must be present in re-laid-out output")
        assertTrue(result.contains("#005b1d"), "Start green stroke colour must survive re-layout")
        assertTrue(result.contains("#b5efcd"), "Start green fill colour must survive re-layout")
        assertTrue(result.contains("#831311"), "End red stroke colour must survive re-layout")
        assertTrue(result.contains("#ffcdd2"), "End red fill colour must survive re-layout")
    }

    @Test
    fun `re-layout produces exactly one diagram`() {
        val inputWithDi = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
                  id="Def_dup" targetNamespace="https://test">
  <bpmn:process id="Proc_dup" isExecutable="true">
    <bpmn:startEvent id="S_dup"><bpmn:outgoing>F_dup</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="E_dup"><bpmn:incoming>F_dup</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F_dup" sourceRef="S_dup" targetRef="E_dup"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="OldDiagram_1">
    <bpmndi:BPMNPlane id="OldPlane_1" bpmnElement="Proc_dup">
      <bpmndi:BPMNShape id="OldShape_S" bpmnElement="S_dup">
        <dc:Bounds x="0" y="0" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""

        val result = layouter.layout(inputWithDi)

        // Count BPMNDiagram occurrences in output — must be exactly one.
        val diagCount = "<bpmndi:BPMNDiagram".toRegex().findAll(result).count()
        assertTrue(diagCount == 1, "Output must have exactly 1 BPMNDiagram, found $diagCount")
    }
}
