/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.MessageFlowEdges
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per-processor contract tests for [MessageFlowEdges] (AD-557-14).
 *
 * Asserts the postcondition: [PlacementContext.edges] contains a two-waypoint straight line
 * from source right-edge midpoint to target left-edge midpoint for each MessageFlow.
 */
class MessageFlowEdgesTest {

    private val twoPools = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def1" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="P1" processRef="Proc_1"/>
    <bpmn:participant id="P2" processRef="Proc_2"/>
    <bpmn:messageFlow id="MF1" sourceRef="Task_A" targetRef="Task_B"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="Task_A"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="S1" targetRef="Task_A"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Task_A" targetRef="E1"/>
  </bpmn:process>
  <bpmn:process id="Proc_2" isExecutable="true">
    <bpmn:startEvent id="S2"><bpmn:outgoing>F3</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="Task_B"><bpmn:incoming>F3</bpmn:incoming><bpmn:outgoing>F4</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E2"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F3" sourceRef="S2" targetRef="Task_B"/>
    <bpmn:sequenceFlow id="F4" sourceRef="Task_B" targetRef="E2"/>
  </bpmn:process>
</bpmn:definitions>"""

    private val blackBoxPool = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def2" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_2">
    <bpmn:participant id="P_wb" processRef="Proc_wb"/>
    <bpmn:participant id="P_bb" name="Black Box"/>
    <bpmn:messageFlow id="MF_out" sourceRef="Task_wb" targetRef="P_bb"/>
    <bpmn:messageFlow id="MF_in" sourceRef="P_bb" targetRef="Task_wb"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_wb" isExecutable="true">
    <bpmn:startEvent id="S_wb"><bpmn:outgoing>F_wb1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="Task_wb"><bpmn:incoming>F_wb1</bpmn:incoming><bpmn:outgoing>F_wb2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E_wb"><bpmn:incoming>F_wb2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F_wb1" sourceRef="S_wb" targetRef="Task_wb"/>
    <bpmn:sequenceFlow id="F_wb2" sourceRef="Task_wb" targetRef="E_wb"/>
  </bpmn:process>
</bpmn:definitions>"""

    private val labeledMsgFlow = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def3" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_3">
    <bpmn:participant id="P1" processRef="Proc_1"/>
    <bpmn:participant id="P2" processRef="Proc_2"/>
    <bpmn:messageFlow id="MF_labeled" name="Order Confirmation" sourceRef="Task_A" targetRef="Task_B"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="Task_A"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="S1" targetRef="Task_A"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Task_A" targetRef="E1"/>
  </bpmn:process>
  <bpmn:process id="Proc_2" isExecutable="true">
    <bpmn:startEvent id="S2"><bpmn:outgoing>F3</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="Task_B"><bpmn:incoming>F3</bpmn:incoming><bpmn:outgoing>F4</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E2"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F3" sourceRef="S2" targetRef="Task_B"/>
    <bpmn:sequenceFlow id="F4" sourceRef="Task_B" targetRef="E2"/>
  </bpmn:process>
</bpmn:definitions>"""

    private fun makeCtx(xml: String, shapes: Map<String, Rect>): PlacementContext {
        val model = PlacementTestSkeletons.parse(xml)
        val root = ElkGraphUtil.createGraph()
        return PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
    }

    @Test
    fun `message flow between two tasks produces two waypoints`() {
        // Task_A at (100,50,100,80), Task_B at (500,50,100,80)
        val taskA = Rect(100.0, 50.0, 100.0, 80.0)
        val taskB = Rect(500.0, 50.0, 100.0, 80.0)
        val ctx = makeCtx(twoPools, mapOf("Task_A" to taskA, "Task_B" to taskB))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MF1"]
        assertNotNull(wps, "MF1 must have waypoints")
        assertEquals(2, wps.size, "Message flow must have exactly 2 waypoints (straight line)")

        // Source: Task_A right-edge midpoint
        assertEquals(taskA.x + taskA.w, wps[0].x, "First waypoint x must be Task_A right edge")
        assertEquals(taskA.y + taskA.h / 2.0, wps[0].y, "First waypoint y must be Task_A mid y")

        // Target: Task_B left-edge midpoint
        assertEquals(taskB.x, wps[1].x, "Last waypoint x must be Task_B left edge")
        assertEquals(taskB.y + taskB.h / 2.0, wps[1].y, "Last waypoint y must be Task_B mid y")
    }

    @Test
    fun `message flow to black-box participant uses participant border`() {
        val taskWb = Rect(100.0, 100.0, 100.0, 80.0)
        val pBb = Rect(400.0, 80.0, 100.0, 60.0) // black-box participant shape
        val ctx = makeCtx(blackBoxPool, mapOf("Task_wb" to taskWb, "P_bb" to pBb))

        MessageFlowEdges.process(ctx)

        val wpOut = ctx.edges["MF_out"]
        assertNotNull(wpOut, "MF_out must have waypoints")
        assertEquals(2, wpOut.size, "Message flow to black-box must have 2 waypoints")
        // Source: Task_wb right edge
        assertEquals(taskWb.x + taskWb.w, wpOut[0].x, "MF_out source must be Task_wb right edge")
        // Target: P_bb left edge
        assertEquals(pBb.x, wpOut[1].x, "MF_out target must be P_bb left edge")
    }

    @Test
    fun `labeled message flow produces label at segment midpoint`() {
        val taskA = Rect(100.0, 50.0, 100.0, 80.0)
        val taskB = Rect(500.0, 50.0, 100.0, 80.0)
        val ctx = makeCtx(labeledMsgFlow, mapOf("Task_A" to taskA, "Task_B" to taskB))

        MessageFlowEdges.process(ctx)

        val label = ctx.labels["MF_labeled"]
        assertNotNull(label, "Labeled message flow must have a label rect")
        // Label should be horizontally centred between endpoints
        val midX = (taskA.x + taskA.w + taskB.x) / 2.0
        val labelCentreX = label.x + label.w / 2.0
        assertTrue(
            kotlin.math.abs(labelCentreX - midX) < 5.0,
            "Label centre x ($labelCentreX) should be near segment midpoint ($midX)",
        )
        // Label should be above the line (y < midY)
        val midY = (taskA.y + taskA.h / 2.0 + taskB.y + taskB.h / 2.0) / 2.0
        assertTrue(
            label.y < midY,
            "Label top (${label.y}) should be above segment midY ($midY)",
        )
    }

    private val verticalOffsetPools = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def4" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_4">
    <bpmn:participant id="P_top" processRef="Proc_top"/>
    <bpmn:participant id="P_bot" processRef="Proc_bot"/>
    <bpmn:messageFlow id="MF_cross" name="Result" sourceRef="Task_top" targetRef="Task_bot"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_top" isExecutable="true">
    <bpmn:serviceTask id="Task_top" name="Provide Help"/>
  </bpmn:process>
  <bpmn:process id="Proc_bot" isExecutable="true">
    <bpmn:serviceTask id="Task_bot" name="Finalize"/>
  </bpmn:process>
</bpmn:definitions>"""

    @Test
    fun `vertical message flow with different x-centres produces 4-point L-shape`() {
        // Mirrors the collab-subprocess MsgFlow_2 geometry:
        // Task_top in top pool, Task_bot in bottom pool, different x-centres.
        val taskTop = Rect(158.0, 32.0, 100.0, 80.0) // cx=208, bottom=112
        val taskBot = Rect(890.0, 262.0, 100.0, 80.0) // cx=940, top=262
        val ctx = makeCtx(verticalOffsetPools, mapOf("Task_top" to taskTop, "Task_bot" to taskBot))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MF_cross"]
        assertNotNull(wps, "MF_cross must have waypoints")
        assertEquals(4, wps.size, "Cross-x vertical message flow must have 4 waypoints (L-shape)")

        // wp0: source bottom-centre
        assertEquals(208.0, wps[0].x, "wp0 x must be source centre-x")
        assertEquals(112.0, wps[0].y, "wp0 y must be source bottom")
        // wp1: bend down to gap mid-y, same x as source
        assertEquals(208.0, wps[1].x, "wp1 x must be source centre-x")
        val gapMidY = (112.0 + 262.0) / 2.0
        assertEquals(gapMidY, wps[1].y, "wp1 y must be inter-pool gap mid-y")
        // wp2: horizontal to target x, same gap mid-y
        assertEquals(940.0, wps[2].x, "wp2 x must be target centre-x")
        assertEquals(gapMidY, wps[2].y, "wp2 y must be inter-pool gap mid-y")
        // wp3: target top-centre
        assertEquals(940.0, wps[3].x, "wp3 x must be target centre-x")
        assertEquals(262.0, wps[3].y, "wp3 y must be target top")
    }

    @Test
    fun `vertical message flow with same x-centre stays 2-point straight`() {
        // Same x-centre: straight vertical, no L-shape needed.
        val taskTop = Rect(158.0, 32.0, 100.0, 80.0) // cx=208
        val taskBot = Rect(158.0, 262.0, 100.0, 80.0) // cx=208, same
        val ctx = makeCtx(verticalOffsetPools, mapOf("Task_top" to taskTop, "Task_bot" to taskBot))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MF_cross"]
        assertNotNull(wps)
        assertEquals(2, wps.size, "Same-x vertical message flow must stay 2 waypoints")
        assertEquals(208.0, wps[0].x)
        assertEquals(112.0, wps[0].y)
        assertEquals(208.0, wps[1].x)
        assertEquals(262.0, wps[1].y)
    }

    @Test
    fun `non-collaboration model produces no message flow edges`() {
        val flatXml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Flat" targetNamespace="https://test">
  <bpmn:process id="P" isExecutable="true">
    <bpmn:startEvent id="S"><bpmn:outgoing>F</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="E"><bpmn:incoming>F</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F" sourceRef="S" targetRef="E"/>
  </bpmn:process>
</bpmn:definitions>"""
        val ctx = makeCtx(flatXml, mapOf("S" to Rect(0.0, 0.0, 36.0, 36.0)))

        MessageFlowEdges.process(ctx)

        assertTrue(ctx.edges.isEmpty(), "No edges should be added for flat (non-collaboration) model")
    }
}
