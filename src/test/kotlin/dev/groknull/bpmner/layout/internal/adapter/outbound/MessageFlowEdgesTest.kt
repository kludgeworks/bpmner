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
 * Per-processor contract tests for [MessageFlowEdges].
 *
 * Asserts the postcondition: [PlacementContext.edges] contains a two-waypoint straight line
 * from source right-edge midpoint to target left-edge midpoint for each MessageFlow.
 */
class MessageFlowEdgesTest {

    private fun loadFixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("layout-fixtures/$name")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Fixture not found: layout-fixtures/$name")

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
        // collab-msg-endpoint: MsgFlow_1 from Task_A1 to Task_B1 (horizontal).
        val taskA1 = Rect(100.0, 50.0, 100.0, 80.0)
        val taskB1 = Rect(500.0, 50.0, 100.0, 80.0)
        val ctx = makeCtx(loadFixture("collab-msg-endpoint.bpmn"), mapOf("Task_A1" to taskA1, "Task_B1" to taskB1))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MsgFlow_1"]
        assertNotNull(wps, "MsgFlow_1 must have waypoints")
        assertEquals(2, wps.size, "Message flow must have exactly 2 waypoints (straight line)")

        // Source: Task_A1 right-edge midpoint
        assertEquals(taskA1.x + taskA1.w, wps[0].x, "First waypoint x must be Task_A1 right edge")
        assertEquals(taskA1.y + taskA1.h / 2.0, wps[0].y, "First waypoint y must be Task_A1 mid y")

        // Target: Task_B1 left-edge midpoint
        assertEquals(taskB1.x, wps[1].x, "Last waypoint x must be Task_B1 left edge")
        assertEquals(taskB1.y + taskB1.h / 2.0, wps[1].y, "Last waypoint y must be Task_B1 mid y")
    }

    @Test
    fun `message flow to black-box participant uses participant border`() {
        // collab-blackbox: MsgFlow_out from Task_send to Participant_external (black-box).
        val taskSend = Rect(100.0, 100.0, 100.0, 80.0)
        val pExternal = Rect(400.0, 80.0, 100.0, 60.0)
        val ctx = makeCtx(
            loadFixture("collab-blackbox.bpmn"),
            mapOf("Task_send" to taskSend, "Participant_external" to pExternal),
        )

        MessageFlowEdges.process(ctx)

        val wpOut = ctx.edges["MsgFlow_out"]
        assertNotNull(wpOut, "MsgFlow_out must have waypoints")
        assertEquals(2, wpOut.size, "Message flow to black-box must have 2 waypoints")
        // Source: Task_send right edge
        assertEquals(taskSend.x + taskSend.w, wpOut[0].x, "MsgFlow_out source must be Task_send right edge")
        // Target: Participant_external left edge
        assertEquals(pExternal.x, wpOut[1].x, "MsgFlow_out target must be Participant_external left edge")
    }

    @Test
    fun `labeled message flow produces label at segment midpoint`() {
        // collab-two-pools: MsgFlow_1 ("Purchase Order") from Task_order to Task_receive.
        val taskOrder = Rect(100.0, 50.0, 100.0, 80.0)
        val taskReceive = Rect(500.0, 50.0, 100.0, 80.0)
        val ctx = makeCtx(
            loadFixture("collab-two-pools.bpmn"),
            mapOf("Task_order" to taskOrder, "Task_receive" to taskReceive),
        )

        MessageFlowEdges.process(ctx)

        val label = ctx.labels["MsgFlow_1"]
        assertNotNull(label, "Labeled message flow must have a label rect")
        // Label should be horizontally centred between endpoints
        val midX = (taskOrder.x + taskOrder.w + taskReceive.x) / 2.0
        val labelCentreX = label.x + label.w / 2.0
        assertTrue(
            kotlin.math.abs(labelCentreX - midX) < 5.0,
            "Label centre x ($labelCentreX) should be near segment midpoint ($midX)",
        )
    }

    private val verticalOffsetPools = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def_vert" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_vert">
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
        val taskTop = Rect(158.0, 32.0, 100.0, 80.0) // cx=208, bottom=112
        val taskBot = Rect(890.0, 262.0, 100.0, 80.0) // cx=940, top=262
        val ctx = makeCtx(verticalOffsetPools, mapOf("Task_top" to taskTop, "Task_bot" to taskBot))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MF_cross"]
        assertNotNull(wps, "MF_cross must have waypoints")
        assertEquals(4, wps.size, "Cross-x vertical message flow must have 4 waypoints (L-shape)")

        assertEquals(208.0, wps[0].x, "wp0 x must be source centre-x")
        assertEquals(112.0, wps[0].y, "wp0 y must be source bottom")
        assertEquals(208.0, wps[1].x, "wp1 x must be source centre-x")
        val gapMidY = (112.0 + 262.0) / 2.0
        assertEquals(gapMidY, wps[1].y, "wp1 y must be inter-pool gap mid-y")
        assertEquals(940.0, wps[2].x, "wp2 x must be target centre-x")
        assertEquals(gapMidY, wps[2].y, "wp2 y must be inter-pool gap mid-y")
        assertEquals(940.0, wps[3].x, "wp3 x must be target centre-x")
        assertEquals(262.0, wps[3].y, "wp3 y must be target top")
    }

    @Test
    fun `upward vertical message flow enters target from its bottom edge`() {
        val source = Rect(158.0, 292.0, 100.0, 80.0) // cx=208, top=292 (bottom pool)
        val target = Rect(890.0, 82.0, 100.0, 80.0) // cx=940, bottom=162 (top pool)
        val ctx = makeCtx(verticalOffsetPools, mapOf("Task_top" to source, "Task_bot" to target))

        MessageFlowEdges.process(ctx)

        val wps = ctx.edges["MF_cross"]
        assertNotNull(wps, "MF_cross must have waypoints")
        assertEquals(4, wps.size, "Cross-x vertical message flow must have 4 waypoints (L-shape)")

        assertEquals(208.0, wps[0].x, "wp0 x must be source centre-x")
        assertEquals(292.0, wps[0].y, "wp0 y must be source top")
        assertEquals(940.0, wps[3].x, "wp3 x must be target centre-x")
        assertEquals(162.0, wps[3].y, "wp3 y must be target bottom (162), not source y")
    }

    @Test
    fun `vertical message flow with same x-centre stays 2-point straight`() {
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
