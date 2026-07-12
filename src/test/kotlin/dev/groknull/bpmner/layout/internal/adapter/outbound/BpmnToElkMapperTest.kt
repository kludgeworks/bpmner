/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layer 1: asserts ELK graph structure produced by [BpmnToElkMapper].
 * Does NOT run the layout engine — inspects the graph object model directly.
 */
class BpmnToElkMapperTest {

    // ── Subprocess containment ────────────────────────────────────────────────

    @Test
    fun `subprocess becomes compound node with children inside it`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="Sub_1">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>SF1</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="SF1" sourceRef="SubStart" targetRef="SubEnd"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Sub_1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Sub_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>"""
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        val compound = result.nodeMap["Sub_1"]
        assertNotNull(compound, "Sub_1 should be in nodeMap")

        // Subprocess children must be children of the compound node, not root siblings
        val subStart = result.nodeMap["SubStart"]
        val subEnd = result.nodeMap["SubEnd"]
        assertNotNull(subStart, "SubStart must be in nodeMap")
        assertNotNull(subEnd, "SubEnd must be in nodeMap")
        assertEquals(compound, subStart.parent, "SubStart parent must be the compound node")
        assertEquals(compound, subEnd.parent, "SubEnd parent must be the compound node")

        // Outer nodes must be root children, not inside the compound
        val startNode = result.nodeMap["Start_1"]
        val endNode = result.nodeMap["End_1"]
        assertNotNull(startNode)
        assertNotNull(endNode)
        assertEquals(result.root, startNode.parent, "Start_1 should be a root child")
        assertEquals(result.root, endNode.parent, "End_1 should be a root child")
    }

    @Test
    fun `two-level subprocess produces two-level compound node tree`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="Outer">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
      <bpmn:startEvent id="OuterStart"><bpmn:outgoing>OF1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:subProcess id="Inner">
        <bpmn:incoming>OF1</bpmn:incoming><bpmn:outgoing>OF2</bpmn:outgoing>
        <bpmn:startEvent id="InnerStart"><bpmn:outgoing>IF1</bpmn:outgoing></bpmn:startEvent>
        <bpmn:endEvent id="InnerEnd"><bpmn:incoming>IF1</bpmn:incoming></bpmn:endEvent>
        <bpmn:sequenceFlow id="IF1" sourceRef="InnerStart" targetRef="InnerEnd"/>
      </bpmn:subProcess>
      <bpmn:endEvent id="OuterEnd"><bpmn:incoming>OF2</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="OF1" sourceRef="OuterStart" targetRef="Inner"/>
      <bpmn:sequenceFlow id="OF2" sourceRef="Inner" targetRef="OuterEnd"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Outer"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Outer" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>"""
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        val outer = result.nodeMap["Outer"]
        val inner = result.nodeMap["Inner"]
        val innerStart = result.nodeMap["InnerStart"]
        assertNotNull(outer)
        assertNotNull(inner)
        assertNotNull(innerStart)

        assertEquals(result.root, outer.parent, "Outer parent is root")
        assertEquals(outer, inner.parent, "Inner parent is outer compound")
        assertEquals(inner, innerStart.parent, "InnerStart parent is inner compound")
    }

    @Test
    fun `intra-subprocess sequence flow is contained in subprocess compound node`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="Sub_1">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>SF1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>SF1</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="SF1" sourceRef="SubStart" targetRef="SubEnd"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Sub_1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Sub_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>"""
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        val compound = result.nodeMap["Sub_1"]!!
        val intraEdge = result.edgeMap["SF1"]
        assertNotNull(intraEdge, "SF1 must be in edgeMap")
        assertEquals(compound, intraEdge.containingNode, "SF1 must be contained in the compound node")
    }

    // ── Boundary event mapping ─────────────────────────────────────────────────

    @Test
    fun `boundary event produces port on host and sibling node in host container`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        // nodeMap must contain the boundary event as a sibling node
        val beNode = result.nodeMap["Boundary_1"]
        assertNotNull(beNode, "Boundary_1 must be in nodeMap")

        // portMap must contain the boundary event ID
        val port = result.portMap["Boundary_1"]
        assertNotNull(port, "Boundary_1 must be in portMap")

        // Host node must have the port
        val hostNode = result.nodeMap["Task_1"]!!
        assertTrue(hostNode.ports.contains(port), "Host node must own the boundary port")

        // Sibling node must be in the same container as the host (the root)
        assertEquals(hostNode.parent, beNode.parent, "Boundary node must be sibling of host in same container")
    }

    @Test
    fun `exception flow from boundary event is contained in root (cross-hierarchy LCA)`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        val exceptionEdge = result.edgeMap["Flow_exception"]
        assertNotNull(exceptionEdge, "Flow_exception must be in edgeMap")
        // LCA of port (on root-level host) and root-level target is the root
        assertEquals(result.root, exceptionEdge.containingNode, "Exception flow must be contained in root")
    }

    @Test
    fun `nodeMap includes subprocess, boundary event, and all child IDs`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        listOf("Start_1", "Task_1", "Boundary_1", "End_ok", "Task_cancel", "End_cancel").forEach { id ->
            assertNotNull(result.nodeMap[id], "$id must be in nodeMap")
        }
    }

    @Test
    fun `normal flow source not in portMap falls back to nodeMap`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        // Flow_normal goes Task_1 → End_ok; Task_1 is not a boundary event so not in portMap
        val normalEdge = result.edgeMap["Flow_normal"]
        assertNotNull(normalEdge, "Flow_normal must be in edgeMap")
        assertNull(result.portMap["Task_1"], "Task_1 must not be in portMap")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("MaxLineLength")
    private fun parseXml(xml: String): BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    companion object {
        const val BOUNDARY_TIMER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_start</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1">
      <bpmn:incoming>Flow_start</bpmn:incoming>
      <bpmn:outgoing>Flow_normal</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:boundaryEvent id="Boundary_1" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_exception</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD1"/>
    </bpmn:boundaryEvent>
    <bpmn:endEvent id="End_ok"><bpmn:incoming>Flow_normal</bpmn:incoming></bpmn:endEvent>
    <bpmn:serviceTask id="Task_cancel">
      <bpmn:incoming>Flow_exception</bpmn:incoming>
      <bpmn:outgoing>Flow_cancel</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_cancel"><bpmn:incoming>Flow_cancel</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_start"     sourceRef="Start_1"   targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_normal"    sourceRef="Task_1"     targetRef="End_ok"/>
    <bpmn:sequenceFlow id="Flow_exception" sourceRef="Boundary_1" targetRef="Task_cancel"/>
    <bpmn:sequenceFlow id="Flow_cancel"    sourceRef="Task_cancel" targetRef="End_cancel"/>
  </bpmn:process>
</bpmn:definitions>"""
    }
}
