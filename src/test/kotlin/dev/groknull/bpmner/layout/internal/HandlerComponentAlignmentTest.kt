/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeEdge
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeNode
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makePort
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.parse
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.skeleton
import dev.groknull.bpmner.layout.internal.placement.HandlerComponentAlignment.HANDLER_COMPONENT_X_GAP
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for [HandlerComponentAlignment] (pipeline entries 2 + 9).
 *
 * AD-557-14: the three moving conventions each require a contract test. This file covers
 * HandlerComponentAlignment.Move (rigid whole-component X-translation) and .Repair
 * (waypoint re-anchoring for rejoin / forward-to-handler flows).
 */
class HandlerComponentAlignmentTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        /** Model: start → task → end (main flow), timer boundary → handler end. */
        private val BOUNDARY_MODEL = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:endEvent id="Handler_1"/>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Task_1" targetRef="End_1"/>
    <bpmn:boundaryEvent id="Boundary_1" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_ex</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD1"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_ex" sourceRef="Boundary_1" targetRef="Handler_1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )
    }

    /**
     * Builds a skeleton where handler node starts at x=0 (to the left of the host),
     * simulating ELK's disconnected component independence.
     *
     * Host Task_1 is at x=100, right edge at x=200.
     * Handler_1 starts at x=0 — should be shifted to ≥ 200 + HANDLER_COMPONENT_X_GAP.
     */
    private fun handlerAtZeroSkeleton(): BpmnToElkMapper.ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val start = makeNode(root, "Start_1", 12.0, 57.0, 36.0, 36.0)
        val task = makeNode(root, "Task_1", 100.0, 40.0, 100.0, 80.0)
        val end = makeNode(root, "End_1", 350.0, 57.0, 36.0, 36.0)
        val handler = makeNode(root, "Handler_1", 0.0, 150.0, 36.0, 36.0)
        val beNode = makeNode(root, "Boundary_1", 0.0, 0.0, BpmnToElkMapper.EVENT_SIZE, BpmnToElkMapper.EVENT_SIZE)
        val port = makePort(task, "port_Boundary_1", 45.0, task.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE)
        val f1 = makeEdge(root, "F1", start, task, 48.0, 75.0, 100.0, 80.0)
        val f2 = makeEdge(root, "F2", task, end, 200.0, 80.0, 350.0, 75.0)
        // Flow_ex has no ELK edge — exception edges are bespoke-routed in phase 2 (AD-557-12)
        return skeleton(
            root = root,
            nodeMap = mapOf(
                "Start_1" to start,
                "Task_1" to task,
                "End_1" to end,
                "Handler_1" to handler,
                "Boundary_1" to beNode,
            ),
            portMap = mapOf("Boundary_1" to port),
            edgeMap = mapOf("F1" to f1, "F2" to f2),
        )
    }

    @Test
    fun `Move shifts handler component left edge to at least hostRight + gap`() {
        val sk = handlerAtZeroSkeleton()
        val layout = BpmnPlacementPass.place(BOUNDARY_MODEL, sk)

        val hostRect = layout.shapes["Task_1"]!!
        val handlerRect = layout.shapes["Handler_1"]
        assertNotNull(handlerRect, "Handler_1 must be in shapes")

        val hostRight = hostRect.x + hostRect.w
        assertTrue(
            handlerRect.x >= hostRight + HANDLER_COMPONENT_X_GAP - 0.5,
            "Handler left edge (${handlerRect.x}) must be ≥ hostRight ($hostRight) + gap ($HANDLER_COMPONENT_X_GAP)",
        )
    }

    @Test
    fun `Move records every moved node in the ledger with owner HandlerComponentAlignment`() {
        val sk = handlerAtZeroSkeleton()
        val ctx = dev.groknull.bpmner.layout.internal.placement.PlacementContext(
            model = BOUNDARY_MODEL,
            skeleton = sk,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        BpmnPlacementPass.run(ctx)

        // Handler_1 must be in the move ledger
        val record = ctx.moves["Handler_1"]
        assertNotNull(record, "Handler_1 must have a MoveRecord in the ledger")
        assertTrue(
            record.owner == "HandlerComponentAlignment",
            "Move owner must be HandlerComponentAlignment, was ${record.owner}",
        )
        assertTrue(record.dx > 0.0, "dx must be positive (rightward shift), was ${record.dx}")
    }

    @Test
    fun `Move preserves intra-component relative offsets (rigid translation)`() {
        // Build a model with two handler tasks in a chain
        val model = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
    <bpmn:serviceTask id="Handler_A"><bpmn:outgoing>Fh</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:endEvent id="Handler_End"/>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Task_1" targetRef="End_1"/>
    <bpmn:sequenceFlow id="Fh" sourceRef="Handler_A" targetRef="Handler_End"/>
    <bpmn:boundaryEvent id="Boundary_1" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_ex</bpmn:outgoing>
      <bpmn:timerEventDefinition id="TD1"/>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_ex" sourceRef="Boundary_1" targetRef="Handler_A"/>
  </bpmn:process>
</bpmn:definitions>""",
        )
        val root = ElkGraphUtil.createGraph()
        val start = makeNode(root, "Start_1", 12.0, 57.0, 36.0, 36.0)
        val task = makeNode(root, "Task_1", 100.0, 40.0, 100.0, 80.0)
        val end = makeNode(root, "End_1", 350.0, 57.0, 36.0, 36.0)
        // Handler chain starts at x=0 — both nodes at x=0, offset between them = 150
        val handlerA = makeNode(root, "Handler_A", 0.0, 150.0, 100.0, 80.0)
        val handlerEnd = makeNode(root, "Handler_End", 150.0, 167.0, 36.0, 36.0)
        val beNode = makeNode(root, "Boundary_1", 0.0, 0.0, BpmnToElkMapper.EVENT_SIZE, BpmnToElkMapper.EVENT_SIZE)
        val port = makePort(task, "port_Boundary_1", 45.0, task.height - BpmnToElkMapper.BOUNDARY_PORT_SIZE)
        val f1 = makeEdge(root, "F1", start, task, 48.0, 75.0, 100.0, 80.0)
        val f2 = makeEdge(root, "F2", task, end, 200.0, 80.0, 350.0, 75.0)
        val sk = skeleton(
            root,
            mapOf(
                "Start_1" to start,
                "Task_1" to task,
                "End_1" to end,
                "Handler_A" to handlerA,
                "Handler_End" to handlerEnd,
                "Boundary_1" to beNode,
            ),
            portMap = mapOf("Boundary_1" to port),
            edgeMap = mapOf("F1" to f1, "F2" to f2),
        )

        val originalRelativeX = handlerEnd.x - handlerA.x // 150.0

        val layout = BpmnPlacementPass.place(model, sk)
        val placedA = layout.shapes["Handler_A"]!!
        val placedEnd = layout.shapes["Handler_End"]!!
        val placedRelativeX = placedEnd.x - placedA.x

        assertTrue(
            abs(placedRelativeX - originalRelativeX) < 0.5,
            "Intra-component relative X must be preserved (rigid translation). " +
                "Before: $originalRelativeX After: $placedRelativeX",
        )
    }

    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun `Repair re-anchors rejoin and forward waypoints to shifted shapes`() {
        val model = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F1</bpmn:outgoing><bpmn:outgoing>Flow_forward</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_1">
      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
    </bpmn:serviceTask>
    <bpmn:endEvent id="End_1"><bpmn:incoming>F2</bpmn:incoming><bpmn:incoming>Flow_rejoin</bpmn:incoming></bpmn:endEvent>
    <bpmn:boundaryEvent id="Boundary_1" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:outgoing>Flow_ex</bpmn:outgoing>
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_ex" sourceRef="Boundary_1" targetRef="Handler_1"/>
    <bpmn:serviceTask id="Handler_1"><bpmn:incoming>Flow_ex</bpmn:incoming><bpmn:incoming>Flow_forward</bpmn:incoming><bpmn:outgoing>Flow_h</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:serviceTask id="Handler_2"><bpmn:incoming>Flow_h</bpmn:incoming><bpmn:outgoing>Flow_rejoin</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:sequenceFlow id="Flow_h" sourceRef="Handler_1" targetRef="Handler_2"/>
    <bpmn:sequenceFlow id="Flow_rejoin" sourceRef="Handler_2" targetRef="End_1"/>
    <bpmn:sequenceFlow id="Flow_forward" sourceRef="Start_1" targetRef="Handler_1"/>
    <bpmn:sequenceFlow id="F1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="F2" sourceRef="Task_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )

        val shapes = mutableMapOf(
            "Start_1" to Rect(10.0, 50.0, 30.0, 30.0),
            "Task_1" to Rect(100.0, 50.0, 100.0, 80.0),
            "End_1" to Rect(400.0, 50.0, 30.0, 30.0),
            "Handler_1" to Rect(150.0, 200.0, 100.0, 80.0),
            "Handler_2" to Rect(300.0, 200.0, 100.0, 80.0),
        )

        val edges = mutableMapOf(
            "Flow_h" to listOf(Point(150.0, 190.0), Point(200.0, 190.0)),
            "Flow_rejoin" to listOf(Point(300.0, 200.0), Point(400.0, 50.0)),
            "Flow_forward" to listOf(Point(40.0, 65.0), Point(150.0, 200.0)),
        )

        val moves = mutableMapOf(
            "Handler_1" to dev.groknull.bpmner.layout.internal.placement.MoveRecord("HandlerComponentAlignment", 100.0, 50.0),
            "Handler_2" to dev.groknull.bpmner.layout.internal.placement.MoveRecord("HandlerComponentAlignment", 100.0, 50.0),
        )

        val root = ElkGraphUtil.createGraph()
        val sk = skeleton(root, mapOf(), mapOf(), mapOf())

        val ctx = dev.groknull.bpmner.layout.internal.placement.PlacementContext(
            model = model,
            skeleton = sk,
            shapes = shapes,
            labels = mutableMapOf(),
            edges = edges,
            expanded = mutableSetOf(),
            moves = moves,
        )

        dev.groknull.bpmner.layout.internal.placement.HandlerComponentAlignment.Repair.process(ctx)

        val expectedFlowH = listOf(Point(250.0, 240.0), Point(300.0, 240.0))
        kotlin.test.assertEquals(expectedFlowH, edges["Flow_h"], "Flow_h waypoints must be shifted uniformly")

        val expectedRejoin = listOf(Point(400.0, 240.0), Point(415.0, 240.0), Point(415.0, 80.0))
        kotlin.test.assertEquals(expectedRejoin, edges["Flow_rejoin"], "Flow_rejoin must be correctly routed by routeRejoinEdge")

        val expectedForward = listOf(Point(40.0, 65.0), Point(150.0, 240.0))
        kotlin.test.assertEquals(expectedForward, edges["Flow_forward"], "Flow_forward must be correctly routed by routeForwardToHandlerEdge")
    }
}
