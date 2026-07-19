/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.SUBPROCESS_TOP_PADDING
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeEdgeNoEndpoints
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeNode
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.parse
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.skeleton
import dev.groknull.bpmner.layout.internal.placement.LoopBackEdgeArcs.LOOP_ARC_CLEARANCE
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for [LoopBackEdgeArcs] (pipeline entry 6).
 *
 * Verifies: over-the-top orthogonal arcs for loop-back flows excluded from ELK skeleton.
 * Tests both the subprocess-top-padding arc lane AND the fallback clearance above topmost node.
 */
class LoopBackEdgeArcsTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        /** Subprocess with loop-back flow Gw_check → SubTask_1. */
        private val LOOP_MODEL = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:subProcess id="Sub_1">
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>FS0</bpmn:outgoing></bpmn:startEvent>
      <bpmn:serviceTask id="SubTask_1">
        <bpmn:incoming>FS0</bpmn:incoming><bpmn:incoming>Floop</bpmn:incoming>
        <bpmn:outgoing>FS1</bpmn:outgoing>
      </bpmn:serviceTask>
      <bpmn:exclusiveGateway id="Gw_check">
        <bpmn:incoming>FS1</bpmn:incoming><bpmn:outgoing>Fok</bpmn:outgoing><bpmn:outgoing>Floop</bpmn:outgoing>
      </bpmn:exclusiveGateway>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>Fok</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="FS0" sourceRef="SubStart" targetRef="SubTask_1"/>
      <bpmn:sequenceFlow id="FS1" sourceRef="SubTask_1" targetRef="Gw_check"/>
      <bpmn:sequenceFlow id="Fok" sourceRef="Gw_check" targetRef="SubEnd"/>
      <bpmn:sequenceFlow id="Floop" sourceRef="Gw_check" targetRef="SubTask_1"/>
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>""",
        )
    }

    /**
     * Subprocess at (100, 50+SUBPROCESS_TOP_PADDING) so arcLaneY = subTop + SUBPROCESS_TOP_PADDING/2.
     * Gw_check at x=250, SubTask_1 at x=100 → arc goes rightward top of Gw, left top of SubTask_1.
     */
    private fun loopSubprocessSkeleton(): BpmnToElkMapper.ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val topPad = SUBPROCESS_TOP_PADDING
        // Subprocess top at y=50; internal nodes at y=topPad (relative)
        val sub = makeNode(root, "Sub_1", 100.0, 50.0, 500.0, 200.0 + topPad)
        val subStart = makeNode(sub, "SubStart", 20.0, topPad + 42.0, 36.0, 36.0)
        val subTask = makeNode(sub, "SubTask_1", 80.0, topPad + 20.0, 100.0, 80.0)
        val gwCheck = makeNode(sub, "Gw_check", 220.0, topPad + 35.0, 50.0, 50.0)
        val subEnd = makeNode(sub, "SubEnd", 310.0, topPad + 42.0, 36.0, 36.0)
        val fs0 = makeEdgeNoEndpoints(sub, "FS0", 56.0, topPad + 60.0, 80.0, topPad + 60.0)
        val fs1 = makeEdgeNoEndpoints(sub, "FS1", 180.0, topPad + 60.0, 220.0, topPad + 60.0)
        val fok = makeEdgeNoEndpoints(sub, "Fok", 270.0, topPad + 60.0, 310.0, topPad + 60.0)
        // Floop is excluded from ELK (loop-back), so NOT in edgeMap
        return skeleton(
            root,
            mapOf(
                "Sub_1" to sub,
                "SubStart" to subStart,
                "SubTask_1" to subTask,
                "Gw_check" to gwCheck,
                "SubEnd" to subEnd,
            ),
            edgeMap = mapOf("FS0" to fs0, "FS1" to fs1, "Fok" to fok),
            loopBackFlowIds = setOf("Floop"),
        )
    }

    @Test
    fun `arc lane Y is SUBPROCESS_TOP_PADDING div 2 above subprocess top`() {
        val sk = loopSubprocessSkeleton()
        val layout = BpmnPlacementPass.place(LOOP_MODEL, sk)

        val loopWps = layout.edges["Floop"]
        assertNotNull(loopWps, "Loop-back edge Floop must have waypoints")
        assertTrue(loopWps.size == 4, "Loop-back arc must have 4 waypoints, had ${loopWps.size}")

        val subRect = layout.shapes["Sub_1"]!!
        val expectedArcLaneY = subRect.y + SUBPROCESS_TOP_PADDING / 2.0

        // wp1 and wp2 are at the arc lane Y
        assertTrue(
            kotlin.math.abs(loopWps[1].y - expectedArcLaneY) < 0.5,
            "Arc lane Y (wp1.y=${loopWps[1].y}) must be subRect.y + SUBPROCESS_TOP_PADDING/2 ($expectedArcLaneY)",
        )
        assertTrue(
            kotlin.math.abs(loopWps[2].y - expectedArcLaneY) < 0.5,
            "Arc lane Y (wp2.y=${loopWps[2].y}) must equal wp1.y",
        )
    }

    @Test
    fun `arc exits source top and enters target top (orthogonal)`() {
        val sk = loopSubprocessSkeleton()
        val layout = BpmnPlacementPass.place(LOOP_MODEL, sk)

        val loopWps = layout.edges["Floop"]!!
        val srcRect = layout.shapes["Gw_check"]!!
        val tgtRect = layout.shapes["SubTask_1"]!!

        // wp0: source top centre
        val srcCx = srcRect.x + srcRect.w / 2.0
        assertTrue(kotlin.math.abs(loopWps[0].x - srcCx) < 0.5, "wp0.x must be source centre-X")
        assertTrue(kotlin.math.abs(loopWps[0].y - srcRect.y) < 0.5, "wp0.y must be source top")

        // wp3: target top centre
        val tgtCx = tgtRect.x + tgtRect.w / 2.0
        assertTrue(kotlin.math.abs(loopWps[3].x - tgtCx) < 0.5, "wp3.x must be target centre-X")
        assertTrue(kotlin.math.abs(loopWps[3].y - tgtRect.y) < 0.5, "wp3.y must be target top")

        // All segments orthogonal
        for (i in 1 until loopWps.size) {
            val a = loopWps[i - 1]
            val b = loopWps[i]
            assertTrue(
                kotlin.math.abs(a.x - b.x) < 0.5 || kotlin.math.abs(a.y - b.y) < 0.5,
                "Segment $a→$b must be axis-aligned",
            )
        }
    }

    @Test
    fun `fallback uses LOOP_ARC_CLEARANCE above topmost node when no subprocess`() {
        // Model without a subprocess — fallback arc lane
        val model = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="S1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:exclusiveGateway id="Gw"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>Fok</bpmn:outgoing><bpmn:outgoing>Fback</bpmn:outgoing></bpmn:exclusiveGateway>
    <bpmn:endEvent id="E1"><bpmn:incoming>Fok</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F1" sourceRef="S1" targetRef="Gw"/>
    <bpmn:sequenceFlow id="Fok" sourceRef="Gw" targetRef="E1"/>
    <bpmn:sequenceFlow id="Fback" sourceRef="Gw" targetRef="S1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )
        val root = ElkGraphUtil.createGraph()
        val s1 = makeNode(root, "S1", 12.0, 100.0, 36.0, 36.0)
        val gw = makeNode(root, "Gw", 150.0, 93.0, 50.0, 50.0)
        val e1 = makeNode(root, "E1", 300.0, 100.0, 36.0, 36.0)
        val f1 = makeEdgeNoEndpoints(root, "F1", 48.0, 118.0, 150.0, 118.0)
        val fok = makeEdgeNoEndpoints(root, "Fok", 200.0, 118.0, 300.0, 118.0)
        val sk = skeleton(
            root,
            mapOf("S1" to s1, "Gw" to gw, "E1" to e1),
            edgeMap = mapOf("F1" to f1, "Fok" to fok),
            loopBackFlowIds = setOf("Fback"),
        )

        val layout = BpmnPlacementPass.place(model, sk)
        val fbWps = layout.edges["Fback"]
        assertNotNull(fbWps, "Fback must have waypoints")

        // Arc lane should be LOOP_ARC_CLEARANCE above the topmost node
        val topmostY = minOf(layout.shapes["S1"]!!.y, layout.shapes["Gw"]!!.y)
        val expectedArcLane = topmostY - LOOP_ARC_CLEARANCE

        assertTrue(
            kotlin.math.abs(fbWps[1].y - expectedArcLane) < 0.5,
            "Fallback arc lane (${fbWps[1].y}) must be " +
                "topmostY ($topmostY) - LOOP_ARC_CLEARANCE ($LOOP_ARC_CLEARANCE) = $expectedArcLane",
        )
    }
}
