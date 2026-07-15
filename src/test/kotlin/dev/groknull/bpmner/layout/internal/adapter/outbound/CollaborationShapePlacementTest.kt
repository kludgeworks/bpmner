/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_HEIGHT
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.BLACK_BOX_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.CollaborationShapePlacement
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Per-processor contract tests for [CollaborationShapePlacement] (AD-557-14).
 *
 * Builds minimal [PlacementContext] instances with hand-crafted shapes and asserts the
 * processor's postconditions without running the full ELK pipeline.
 */
class CollaborationShapePlacementTest {

    private val collab2PoolsXml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def1" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="P1" name="Pool One" processRef="Proc_1"/>
    <bpmn:participant id="P2" name="Pool Two" processRef="Proc_2"/>
    <bpmn:messageFlow id="MF1" sourceRef="T1" targetRef="T2"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="T1"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="S1" targetRef="T1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="T1" targetRef="E1"/>
  </bpmn:process>
  <bpmn:process id="Proc_2" isExecutable="true">
    <bpmn:startEvent id="S2"><bpmn:outgoing>F3</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="T2"><bpmn:incoming>F3</bpmn:incoming><bpmn:outgoing>F4</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E2"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F3" sourceRef="S2" targetRef="T2"/>
    <bpmn:sequenceFlow id="F4" sourceRef="T2" targetRef="E2"/>
  </bpmn:process>
</bpmn:definitions>"""

    private val collabBlackBoxXml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def2" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_2">
    <bpmn:participant id="P_wb" name="White Box" processRef="Proc_wb"/>
    <bpmn:participant id="P_bb" name="Black Box"/>
    <bpmn:messageFlow id="MF_bb" sourceRef="T_wb" targetRef="P_bb"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_wb" isExecutable="true">
    <bpmn:startEvent id="S_wb"><bpmn:outgoing>F_wb1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="T_wb"><bpmn:incoming>F_wb1</bpmn:incoming><bpmn:outgoing>F_wb2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="E_wb"><bpmn:incoming>F_wb2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F_wb1" sourceRef="S_wb" targetRef="T_wb"/>
    <bpmn:sequenceFlow id="F_wb2" sourceRef="T_wb" targetRef="E_wb"/>
  </bpmn:process>
</bpmn:definitions>"""

    private val collabLanesXml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="Def3" targetNamespace="https://test">
  <bpmn:collaboration id="Collab_3">
    <bpmn:participant id="P_lanes" name="With Lanes" processRef="Proc_lanes"/>
  </bpmn:collaboration>
  <bpmn:process id="Proc_lanes" isExecutable="true">
    <bpmn:laneSet id="LS1">
      <bpmn:lane id="Lane_A" name="Lane A">
        <bpmn:flowNodeRef>NodeA1</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>NodeA2</bpmn:flowNodeRef>
      </bpmn:lane>
      <bpmn:lane id="Lane_B" name="Lane B">
        <bpmn:flowNodeRef>NodeB1</bpmn:flowNodeRef>
      </bpmn:lane>
    </bpmn:laneSet>
    <bpmn:startEvent id="NodeA1"><bpmn:outgoing>FL1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:userTask id="NodeA2"><bpmn:incoming>FL1</bpmn:incoming><bpmn:outgoing>FL2</bpmn:outgoing></bpmn:userTask>
    <bpmn:endEvent id="NodeB1"><bpmn:incoming>FL2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="FL1" sourceRef="NodeA1" targetRef="NodeA2"/>
    <bpmn:sequenceFlow id="FL2" sourceRef="NodeA2" targetRef="NodeB1"/>
  </bpmn:process>
</bpmn:definitions>"""

    @Test
    fun `two nodes in participant - participant bounds enclose both nodes with padding`() {
        val model = PlacementTestSkeletons.parse(collab2PoolsXml)
        val root = ElkGraphUtil.createGraph()
        // Simulate placed flow node shapes for Proc_1 (P1) nodes.
        val nodeMap = mutableMapOf<String, org.eclipse.elk.graph.ElkNode>()
        val shapes = mutableMapOf<String, Rect>(
            "S1" to Rect(32.0, 54.0, 36.0, 36.0),
            "T1" to Rect(158.0, 32.0, 100.0, 80.0),
            "E1" to Rect(348.0, 54.0, 36.0, 36.0),
            "S2" to Rect(32.0, 234.0, 36.0, 36.0),
            "T2" to Rect(158.0, 212.0, 100.0, 80.0),
            "E2" to Rect(348.0, 234.0, 36.0, 36.0),
        )
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, nodeMap),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        val p1Bounds = ctx.shapes["P1"]
        assertNotNull(p1Bounds, "P1 participant must have a shape")
        assertTrue(p1Bounds.w > 0 && p1Bounds.h > 0, "P1 bounds must have positive dimensions")
        // P1 must enclose all Proc_1 nodes with padding
        assertTrue(p1Bounds.x <= 32.0, "P1 left must be <= leftmost node x (32)")
        assertTrue(p1Bounds.y <= 32.0, "P1 top must be <= topmost node y (32)")
        assertTrue(p1Bounds.x + p1Bounds.w >= 384.0, "P1 right must be >= rightmost node right (348+36=384)")
        assertTrue(p1Bounds.y + p1Bounds.h >= 112.0, "P1 bottom must be >= bottommost node bottom (32+80=112)")
    }

    @Test
    fun `participant bounds include PARTICIPANT_HEADER_WIDTH on the left`() {
        val model = PlacementTestSkeletons.parse(collab2PoolsXml)
        val root = ElkGraphUtil.createGraph()
        val shapes = mutableMapOf<String, Rect>(
            "S1" to Rect(50.0, 50.0, 36.0, 36.0),
            "E1" to Rect(200.0, 50.0, 36.0, 36.0),
            "S2" to Rect(50.0, 200.0, 36.0, 36.0),
            "E2" to Rect(200.0, 200.0, 36.0, 36.0),
            "T1" to Rect(100.0, 50.0, 100.0, 80.0),
            "T2" to Rect(100.0, 200.0, 100.0, 80.0),
        )
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        val p1 = ctx.shapes["P1"]
        assertNotNull(p1)
        // The participant x must be to the left of the content area's leftmost node
        val contentMinX = 50.0
        assertTrue(
            p1.x <= contentMinX,
            "Participant x (${p1.x}) must be <= content left ($contentMinX), leaving room for header",
        )
    }

    @Test
    fun `black-box participant gets fixed canonical bounds`() {
        val model = PlacementTestSkeletons.parse(collabBlackBoxXml)
        val root = ElkGraphUtil.createGraph()
        val shapes = mutableMapOf<String, Rect>(
            "S_wb" to Rect(32.0, 54.0, 36.0, 36.0),
            "T_wb" to Rect(158.0, 32.0, 100.0, 80.0),
            "E_wb" to Rect(348.0, 54.0, 36.0, 36.0),
        )
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        val bbBounds = ctx.shapes["P_bb"]
        assertNotNull(bbBounds, "Black-box participant must have a shape")
        assertEquals(BLACK_BOX_WIDTH, bbBounds.w, "Black-box must have canonical width $BLACK_BOX_WIDTH")
        assertEquals(BLACK_BOX_HEIGHT, bbBounds.h, "Black-box must have canonical height $BLACK_BOX_HEIGHT")
    }

    @Test
    fun `participant label rect is in ctx_labels`() {
        val model = PlacementTestSkeletons.parse(collab2PoolsXml)
        val root = ElkGraphUtil.createGraph()
        val shapes = mutableMapOf<String, Rect>(
            "S1" to Rect(32.0, 54.0, 36.0, 36.0),
            "T1" to Rect(158.0, 32.0, 100.0, 80.0),
            "E1" to Rect(348.0, 54.0, 36.0, 36.0),
            "S2" to Rect(32.0, 234.0, 36.0, 36.0),
            "T2" to Rect(158.0, 212.0, 100.0, 80.0),
            "E2" to Rect(348.0, 234.0, 36.0, 36.0),
        )
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        assertNotNull(ctx.labels["P1"], "Named participant P1 must have a label rect")
        assertNotNull(ctx.labels["P2"], "Named participant P2 must have a label rect")
        // Label width should equal PARTICIPANT_HEADER_WIDTH
        val p1Label = ctx.labels["P1"]!!
        assertEquals(PARTICIPANT_HEADER_WIDTH, p1Label.w, "Participant label width must equal PARTICIPANT_HEADER_WIDTH")
    }

    @Test
    fun `two lanes have non-overlapping Y-spans that fill participant height`() {
        val model = PlacementTestSkeletons.parse(collabLanesXml)
        val root = ElkGraphUtil.createGraph()
        // NodeA1, NodeA2 in Lane_A; NodeB1 in Lane_B
        val shapes = mutableMapOf<String, Rect>(
            "NodeA1" to Rect(50.0, 20.0, 36.0, 36.0),
            "NodeA2" to Rect(150.0, 20.0, 100.0, 80.0),
            "NodeB1" to Rect(300.0, 150.0, 36.0, 36.0),
        )
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        val laneA = ctx.shapes["Lane_A"]
        val laneB = ctx.shapes["Lane_B"]
        assertNotNull(laneA, "Lane_A must have a shape")
        assertNotNull(laneB, "Lane_B must have a shape")
        assertTrue(laneA.h > 0, "Lane_A must have positive height")
        assertTrue(laneB.h > 0, "Lane_B must have positive height")

        // The two lanes must not overlap vertically (each in a separate strip)
        val laneABottom = laneA.y + laneA.h
        val laneBTop = laneB.y
        // Lanes are vertically stacked — they must not overlap (one fully above the other,
        // with at most 1px tolerance for rounding)
        val overlap = minOf(laneABottom, laneB.y + laneB.h) - maxOf(laneA.y, laneBTop)
        assertTrue(
            overlap <= 1.0,
            "Lane_A [$laneA.y..$laneABottom] and Lane_B [$laneBTop..${laneB.y + laneB.h}] must not overlap",
        )
    }

    @Test
    fun `non-collaboration model leaves shapes unchanged`() {
        val flatXml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Flat1" targetNamespace="https://test">
  <bpmn:process id="Proc1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="E1"><bpmn:incoming>F1</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="S1" targetRef="E1"/>
  </bpmn:process>
</bpmn:definitions>"""
        val model = PlacementTestSkeletons.parse(flatXml)
        val root = ElkGraphUtil.createGraph()
        val initialShapes = mutableMapOf("S1" to Rect(0.0, 0.0, 36.0, 36.0))
        val ctx = PlacementContext(
            model = model,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = initialShapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        // No collaboration → processor is no-op
        assertEquals(1, ctx.shapes.size, "No new shapes should be added for flat process")
        assertEquals(initialShapes["S1"], ctx.shapes["S1"])
    }
}
