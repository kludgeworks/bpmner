/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Layer 1: asserts ELK graph structure produced by [BpmnToElkMapper].
 * Does NOT run the layout engine — inspects the graph object model directly.
 *
 * Post AD-557-10/AD-557-11/AD-557-12 assertions: the lean skeleton has NO ElkLabels anywhere,
 * all boundary ports are SOUTH, SubProcesses are compound nodes, exception edges are NOT in the
 * ELK skeleton (AD-557-12), and AD-557-11 options are applied (NETWORK_SIMPLEX, model order,
 * LAYER_CONSTRAINT on start/end events, SPACING_COMPONENT_COMPONENT).
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
    fun `exception flow from boundary event is NOT in edgeMap (AD-557-12 - phase-2 edge only)`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        // AD-557-12: boundary exception edges are routed by phase 2, not by ELK.
        // The exception flow must NOT appear in the ELK skeleton's edgeMap.
        assertNull(result.edgeMap["Flow_exception"], "Flow_exception must NOT be in edgeMap (AD-557-12)")
        // The SOUTH port still exists on the host (attachment geometry)
        assertNotNull(result.portMap["Boundary_1"], "SOUTH port for Boundary_1 must still be in portMap")
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
    fun `normal sequence flow is in edgeMap and boundary exception flow is absent (AD-557-12)`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        // Normal flows (non-boundary source) are ELK edges.
        assertNotNull(result.edgeMap["Flow_normal"], "Flow_normal must be in edgeMap")
        assertNotNull(result.edgeMap["Flow_start"], "Flow_start must be in edgeMap")
        assertNotNull(result.edgeMap["Flow_cancel"], "Flow_cancel must be in edgeMap")
        // Boundary-source exception flow must NOT be an ELK edge (AD-557-12).
        assertNull(result.edgeMap["Flow_exception"], "Flow_exception (boundary source) must NOT be in edgeMap")
    }

    // ── AD-557-11/AD-557-12 option assertions ─────────────────────────────────

    @Test
    fun `root options include NETWORK_SIMPLEX and model-order strategy (AD-557-11)`() {
        val model = parseXml(BOUNDARY_TIMER_XML)
        val result = BpmnToElkMapper.map(model)

        val strategy = result.root.getProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY)
        assertEquals(
            NodePlacementStrategy.NETWORK_SIMPLEX,
            strategy,
            "NODE_PLACEMENT_STRATEGY must be NETWORK_SIMPLEX (AD-557-11 straight happy path)",
        )
        val modelOrder = result.root.getProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY)
        assertEquals(
            OrderingStrategy.NODES_AND_EDGES,
            modelOrder,
            "CONSIDER_MODEL_ORDER_STRATEGY must be NODES_AND_EDGES (AD-557-11)",
        )
    }

    @Test
    fun `root option SPACING_COMPONENT_COMPONENT is set for handler row clearance (AD-557-12)`() {
        val model = parseXml(BOUNDARY_TIMER_XML)
        val result = BpmnToElkMapper.map(model)

        val spacing = result.root.getProperty(LayeredOptions.SPACING_COMPONENT_COMPONENT)
        assertTrue(
            spacing != null && spacing >= 20.0,
            "SPACING_COMPONENT_COMPONENT must be set and >=20 to clear handler rows (AD-557-12), was $spacing",
        )
    }

    @Test
    fun `start event node has LAYER_CONSTRAINT FIRST and end event has LAST (AD-557-11)`() {
        val model = parseXml(BOUNDARY_TIMER_XML)
        val result = BpmnToElkMapper.map(model)

        val startNode = result.nodeMap["Start_1"]
        assertNotNull(startNode)
        val startConstraint = startNode.getProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT)
        assertEquals(
            LayerConstraint.FIRST,
            startConstraint,
            "StartEvent must have LAYERING_LAYER_CONSTRAINT = FIRST",
        )

        val endNode = result.nodeMap["End_ok"]
        assertNotNull(endNode)
        val endConstraint = endNode.getProperty(LayeredOptions.LAYERING_LAYER_CONSTRAINT)
        assertEquals(
            LayerConstraint.LAST,
            endConstraint,
            "EndEvent must have LAYERING_LAYER_CONSTRAINT = LAST",
        )
    }

    // ── AD-557-10 lean-skeleton assertions ────────────────────────────────────

    @Test
    fun `lean skeleton has NO ElkLabels on any node (labels are phase-2 responsibility)`() {
        val xml = BOUNDARY_TIMER_XML
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        // Walk every node in the ELK graph and assert no labels
        fun assertNoLabels(node: org.eclipse.elk.graph.ElkNode, path: String) {
            assertTrue(
                node.labels.isEmpty(),
                "ELK node '$path' must have no labels — labels are owned by BpmnPlacementPass",
            )
            for (child in node.children) assertNoLabels(child, "$path/${child.identifier}")
        }
        assertNoLabels(result.root, "root")
    }

    @Test
    fun `all boundary event ports have PORT_SIDE SOUTH (not cycled)`() {
        // Multi-boundary model so we can check that the 2nd port is also SOUTH
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:serviceTask id="Task_1">
      <bpmn:outgoing>Flow_ok</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_ok"><bpmn:incoming>Flow_ok</bpmn:incoming></bpmn:endEvent>
    <bpmn:endEvent id="End_A"><bpmn:incoming>Flow_A</bpmn:incoming></bpmn:endEvent>
    <bpmn:endEvent id="End_B"><bpmn:incoming>Flow_B</bpmn:incoming></bpmn:endEvent>
    <bpmn:boundaryEvent id="Boundary_A" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_A</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD_A"/>
    </bpmn:boundaryEvent>
    <bpmn:boundaryEvent id="Boundary_B" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_B</bpmn:outgoing>
      <bpmn:errorEventDefinition id="ED_B"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_ok" sourceRef="Task_1" targetRef="End_ok"/>
    <bpmn:sequenceFlow id="Flow_A" sourceRef="Boundary_A" targetRef="End_A"/>
    <bpmn:sequenceFlow id="Flow_B" sourceRef="Boundary_B" targetRef="End_B"/>
  </bpmn:process>
</bpmn:definitions>"""
        val model = parseXml(xml)
        val result = BpmnToElkMapper.map(model)

        for (boundaryId in listOf("Boundary_A", "Boundary_B")) {
            val port = result.portMap[boundaryId]
            assertNotNull(port, "$boundaryId must be in portMap")
            val side = port.getProperty(org.eclipse.elk.core.options.CoreOptions.PORT_SIDE)
            assertEquals(
                org.eclipse.elk.core.options.PortSide.SOUTH,
                side,
                "Port for $boundaryId must be SOUTH (AD-557-10) not $side",
            )
        }
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
