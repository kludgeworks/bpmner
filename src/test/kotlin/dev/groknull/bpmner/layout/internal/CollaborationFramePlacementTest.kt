/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.CollaborationFramePlacement
import dev.groknull.bpmner.layout.internal.placement.MoveRecord
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollaborationFramePlacementTest {

    @Test
    fun `projects ordered full-width lane bands from already-banded member shapes without moving them`() {
        // AD-730-06: lanes are never their own ELK node (BpmnToElkMapper's declared band offsets
        // already order and separate members before this pass runs), so the skeleton has no lane
        // compounds, and every member shape below is already in its own lane's final position —
        // this pass only reads that geometry to derive each lane's band rectangle; it moves nothing.
        val model = PlacementTestSkeletons.parse(BpmnToElkMapperTest.COLLABORATION_LANES_XML)
        val root = ElkGraphUtil.createGraph()
        val participant = node(root, "Participant_1", Rect(10.0, 20.0, 500.0, 300.0))
        // Each member sits on its declared band's centreline (70/170/270 for three 100-high bands
        // starting at the participant's top), which is what BpmnToElkMapper maps them onto.
        val start = node(participant, "Start_1", Rect(60.0, 52.0, 36.0, 36.0))
        val pick = node(participant, "Task_pick", Rect(60.0, 130.0, 100.0, 80.0))
        val end = node(participant, "End_1", Rect(60.0, 252.0, 36.0, 36.0))
        val ctx = PlacementContext(
            model,
            PlacementTestSkeletons.skeleton(
                root,
                mapOf("Participant_1" to participant, "Start_1" to start, "Task_pick" to pick, "End_1" to end),
                laneBandHeights = mapOf("Lane_sales" to 100.0, "Lane_warehouse" to 100.0, "Lane_delivery" to 100.0),
            ),
            mutableMapOf(
                "Start_1" to Rect(60.0, 52.0, 36.0, 36.0),
                "Task_pick" to Rect(60.0, 130.0, 100.0, 80.0),
                "End_1" to Rect(60.0, 252.0, 36.0, 36.0),
            ),
            mutableMapOf(),
            mutableMapOf("Flow_1" to listOf(Point(78.0, 70.0), Point(110.0, 70.0), Point(110.0, 130.0))),
            mutableSetOf(),
        )

        CollaborationFramePlacement.process(ctx)

        // The participant frame spans exactly the bands projected inside it.
        assertEquals(Rect(10.0, 20.0, 500.0, 300.0), ctx.shapes["Participant_1"])
        val sales = ctx.shapes.getValue("Lane_sales")
        val warehouse = ctx.shapes.getValue("Lane_warehouse")
        val delivery = ctx.shapes.getValue("Lane_delivery")
        assertEquals(40.0, sales.x)
        assertEquals(20.0, sales.y, "the first lane's band starts at the participant's own top")
        assertEquals(470.0, sales.w)
        assertEquals(sales.y + sales.h, warehouse.y, "adjoining bands share one seam with no gap or overlap")
        assertEquals(warehouse.y + warehouse.h, delivery.y)
        assertEquals(20.0 + 300.0, delivery.y + delivery.h, "the last band extends to the participant's own bottom")
        assertEquals(70.0, sales.y + sales.h / 2.0, "a band's midpoint is its members' shared centreline")
        assertEquals(170.0, warehouse.y + warehouse.h / 2.0)
        assertEquals(270.0, delivery.y + delivery.h / 2.0)

        // Members are never moved: no ledger entry, and their shapes are byte-identical to input.
        assertEquals(Rect(60.0, 52.0, 36.0, 36.0), ctx.shapes["Start_1"])
        assertEquals(null, ctx.moves["Start_1"])
        assertEquals(Rect(60.0, 130.0, 100.0, 80.0), ctx.shapes["Task_pick"])
        assertEquals(
            listOf(Point(78.0, 70.0), Point(110.0, 70.0), Point(110.0, 130.0)),
            ctx.edges["Flow_1"],
            "an untouched flow's already-final ELK route is left byte-for-byte unchanged",
        )
    }

    @Test
    fun `derives a lane band from a subprocess member without moving it, its descendants, or its boundary event`() {
        // AD-730-06: a lane never moves its members — BpmnToElkMapper already declared
        // SubProcess_1's band offset before layout, so its own shape, its descendant, and its
        // attached boundary event's exception route all arrive here already final.
        val model = PlacementTestSkeletons.parse(LANED_SUBPROCESS_WITH_BOUNDARY_XML)
        val root = ElkGraphUtil.createGraph()
        val participant = node(root, "Participant_1", Rect(10.0, 20.0, 500.0, 300.0))
        val subprocess = node(participant, "SubProcess_1", Rect(60.0, 50.0, 200.0, 100.0))
        val child = node(subprocess, "Task_child", Rect(10.0, 10.0, 100.0, 80.0))
        val handler = node(participant, "Task_handler", Rect(290.0, 60.0, 100.0, 80.0))
        val boundary = node(root, "Boundary_1", Rect(80.0, 100.0, 36.0, 36.0))
        val ctx = PlacementContext(
            model,
            PlacementTestSkeletons.skeleton(
                root,
                mapOf(
                    "Participant_1" to participant,
                    "SubProcess_1" to subprocess,
                    "Task_child" to child,
                    "Task_handler" to handler,
                    "Boundary_1" to boundary,
                ),
                laneBandHeights = mapOf("Lane_1" to 160.0),
            ),
            mutableMapOf(
                "SubProcess_1" to Rect(60.0, 50.0, 200.0, 100.0),
                "Task_child" to Rect(70.0, 60.0, 100.0, 80.0),
                "Task_handler" to Rect(290.0, 60.0, 100.0, 80.0),
                "Boundary_1" to Rect(80.0, 90.0, 36.0, 36.0),
            ),
            mutableMapOf(),
            mutableMapOf("Flow_exception" to listOf(Point(98.0, 126.0), Point(290.0, 90.0))),
            mutableSetOf(),
        )

        CollaborationFramePlacement.process(ctx)

        assertEquals(Rect(70.0, 60.0, 100.0, 80.0), ctx.shapes["Task_child"])
        assertEquals(Rect(80.0, 90.0, 36.0, 36.0), ctx.shapes["Boundary_1"])
        assertEquals(null, ctx.moves["Task_child"])
        assertEquals(null, ctx.moves["Boundary_1"])
        assertEquals(listOf(Point(98.0, 126.0), Point(290.0, 90.0)), ctx.edges["Flow_exception"])
        val lane = ctx.shapes.getValue("Lane_1")
        assertEquals(40.0, lane.x)
        assertEquals(470.0, lane.w)
    }

    @Test
    fun `keeps ELK black-box bounds for message-flow endpoints`() {
        val root = ElkGraphUtil.createGraph()
        val external = node(root, "Participant_external", Rect(440.0, 260.0, 100.0, 60.0))
        val ctx = PlacementContext(
            PlacementTestSkeletons.parse(BLACK_BOX_COLLABORATION_XML),
            PlacementTestSkeletons.skeleton(root, mapOf("Participant_external" to external)),
            mutableMapOf(),
            mutableMapOf(),
            mutableMapOf(),
            mutableSetOf(),
        )

        CollaborationFramePlacement.process(ctx)

        assertEquals(Rect(440.0, 260.0, 100.0, 60.0), ctx.shapes["Participant_external"])
        assertEquals(Rect(440.0, 260.0, 30.0, 60.0), ctx.labels["Participant_external"])
    }

    @Test
    fun `stacks two white-box pools into a shared full-width band and ledgers the move`() {
        val ctx = twoPoolContext()

        CollaborationFramePlacement.process(ctx)

        assertEquals(Rect(10.0, 20.0, 400.0, 200.0), ctx.shapes["A"])
        assertEquals(Rect(10.0, 300.0, 400.0, 180.0), ctx.shapes["B"])
        assertEquals(MoveRecord("CollaborationFramePlacement", -490.0, 280.0), ctx.moves["B"])
        assertEquals(MoveRecord("CollaborationFramePlacement", -490.0, 280.0), ctx.moves["Task_b"])
    }

    @Test
    fun `translates descendants without changing their position relative to their own pool`() {
        val ctx = twoPoolContext()

        CollaborationFramePlacement.process(ctx)

        val pool = ctx.shapes.getValue("B")
        val task = ctx.shapes.getValue("Task_b")
        assertEquals(60.0 to 40.0, (task.x - pool.x) to (task.y - pool.y))
    }

    @Test
    fun `routes a cross-pool message flow between the facing edges with a midpoint label`() {
        val ctx = twoPoolContext()

        CollaborationFramePlacement.process(ctx)

        assertEquals(
            listOf(Point(120.0, 340.0), Point(120.0, 240.0), Point(250.0, 240.0), Point(250.0, 140.0)),
            ctx.edges["Msg_1"],
        )
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions("Ping", BpmnPlacementPass.EDGE_LABEL_WIDTH)
        assertEquals(Rect(185.0 - width / 2.0, 240.0 - height / 2.0, width, height), ctx.labels["Msg_1"])
    }

    @Test
    fun `leaves a flow entirely inside an untranslated pool byte-for-byte unchanged`() {
        val ctx = twoPoolContext()
        val original = ctx.edges.getValue("Flow_a")

        CollaborationFramePlacement.process(ctx)

        assertEquals(original, ctx.edges["Flow_a"])
    }

    @Test
    fun `leaves a single-participant collaboration untouched`() {
        val root = ElkGraphUtil.createGraph()
        val participant = PlacementTestSkeletons.makeNode(root, "Solo", 10.0, 20.0, 400.0, 200.0)
        val shapes = mutableMapOf("Solo" to Rect(10.0, 20.0, 400.0, 200.0))
        val labels = mutableMapOf("Solo" to Rect(10.0, 20.0, 30.0, 200.0))
        val edges = mutableMapOf<String, List<Point>>()
        val ctx = PlacementContext(
            PlacementTestSkeletons.parse(SOLO_COLLABORATION_XML),
            PlacementTestSkeletons.skeleton(root, mapOf("Solo" to participant)),
            shapes,
            labels,
            edges,
            mutableSetOf(),
        )

        CollaborationFramePlacement.process(ctx)

        assertEquals(mapOf("Solo" to Rect(10.0, 20.0, 400.0, 200.0)), ctx.shapes)
        assertEquals(mapOf("Solo" to Rect(10.0, 20.0, 30.0, 200.0)), ctx.labels)
        assertEquals(emptyMap(), ctx.edges)
        assertEquals(emptyMap(), ctx.moves)
    }

    /**
     * Two white-box participants, A (Task_a → Task_a2) above B (Task_b), with a cross-pool
     * message flow Task_b → Task_a. A's band already sits at the shared left edge (`bandX`),
     * so A's vector is zero; B is offset right, so B's translation is non-trivial — exercising
     * both the untranslated- and translated-pool repair paths in one fixture.
     */
    private fun twoPoolContext(): PlacementContext {
        val root = ElkGraphUtil.createGraph()
        val a = PlacementTestSkeletons.makeNode(root, "A", 10.0, 20.0, 400.0, 200.0)
        val b = PlacementTestSkeletons.makeNode(root, "B", 500.0, 20.0, 350.0, 180.0)
        val taskA = PlacementTestSkeletons.makeNode(root, "Task_a", 200.0, 60.0, 100.0, 80.0)
        val taskA2 = PlacementTestSkeletons.makeNode(root, "Task_a2", 320.0, 60.0, 100.0, 80.0)
        val taskB = PlacementTestSkeletons.makeNode(root, "Task_b", 560.0, 60.0, 100.0, 80.0)
        return PlacementContext(
            PlacementTestSkeletons.parse(TWO_POOL_XML),
            PlacementTestSkeletons.skeleton(
                root,
                mapOf("A" to a, "B" to b, "Task_a" to taskA, "Task_a2" to taskA2, "Task_b" to taskB),
            ),
            mutableMapOf(
                "A" to Rect(10.0, 20.0, 400.0, 200.0),
                "B" to Rect(500.0, 20.0, 350.0, 180.0),
                "Task_a" to Rect(200.0, 60.0, 100.0, 80.0),
                "Task_a2" to Rect(320.0, 60.0, 100.0, 80.0),
                "Task_b" to Rect(560.0, 60.0, 100.0, 80.0),
            ),
            mutableMapOf(),
            mutableMapOf("Flow_a" to listOf(Point(300.0, 100.0), Point(320.0, 100.0))),
            mutableSetOf(),
        )
    }

    @Test
    fun `regenerates a direct route from the fixed task to the relocated band`() {
        val ctx = blackBoxContext()

        CollaborationFramePlacement.process(ctx)

        assertEquals(Rect(200.0, 300.0, 500.0, 60.0), ctx.shapes["External"])
        assertEquals(listOf(Point(450.0, 160.0), Point(450.0, 300.0)), ctx.edges["Message"])
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions("Request", BpmnPlacementPass.EDGE_LABEL_WIDTH)
        assertEquals(Rect(450.0 - width / 2.0, 230.0 - height / 2.0, width, height), ctx.labels["Message"])
    }

    @Test
    fun `regenerates a direct route from the relocated band to the fixed task`() {
        val ctx = blackBoxContext(source = "External", target = "Task")

        CollaborationFramePlacement.process(ctx)

        assertEquals(listOf(Point(450.0, 300.0), Point(450.0, 160.0)), ctx.edges["Message"])
    }

    @Test
    fun `leaves a message flow it does not reattach byte-for-byte unchanged`() {
        val ctx = blackBoxContext()
        val otherRoute = listOf(Point(1.0, 2.0), Point(3.0, 4.0), Point(5.0, 6.0))
        val otherLabel = Rect(7.0, 8.0, 90.0, 20.0)
        ctx.edges["Other"] = otherRoute
        ctx.labels["Other"] = otherLabel

        CollaborationFramePlacement.process(ctx)

        assertEquals(otherRoute, ctx.edges["Other"])
        assertEquals(otherLabel, ctx.labels["Other"])
    }

    @Test
    fun `bands the external participant below two stacked modeled pools, not just the first`() {
        val root = ElkGraphUtil.createGraph()
        val external = PlacementTestSkeletons.makeNode(root, "External", 10.0, 20.0, 100.0, 60.0)
        val ctx = PlacementContext(
            PlacementTestSkeletons.parse(TWO_MODELED_POOLS_XML),
            PlacementTestSkeletons.skeleton(root, mapOf("External" to external)),
            mutableMapOf(
                "Internal" to Rect(200.0, 40.0, 500.0, 180.0),
                "Internal2" to Rect(200.0, 240.0, 500.0, 100.0),
                "External" to Rect(10.0, 20.0, 100.0, 60.0),
                "Task" to Rect(400.0, 300.0, 100.0, 80.0),
            ),
            mutableMapOf("Message" to Rect(0.0, 0.0, 90.0, 20.0)),
            mutableMapOf("Message" to listOf(Point(0.0, 0.0), Point(1.0, 1.0), Point(2.0, 2.0))),
            mutableSetOf(),
        )

        CollaborationFramePlacement.process(ctx)

        val band = ctx.shapes.getValue("External")
        // Below BOTH modeled pools, spanning their shared left/width.
        assertEquals(200.0, band.x)
        assertEquals(500.0, band.w)
        assertTrue(band.y >= 340.0, "band ($band) must sit below the second modeled pool's bottom (340.0)")
        assertEquals(listOf(Point(450.0, 380.0), Point(450.0, band.y)), ctx.edges["Message"])
    }

    private fun blackBoxContext(source: String = "Task", target: String = "External"): PlacementContext {
        val root = ElkGraphUtil.createGraph()
        val external = PlacementTestSkeletons.makeNode(root, "External", 10.0, 20.0, 100.0, 60.0)
        return PlacementContext(
            PlacementTestSkeletons.parse(blackBoxXml(source, target)),
            PlacementTestSkeletons.skeleton(root, mapOf("External" to external)),
            mutableMapOf(
                "Internal" to Rect(200.0, 40.0, 500.0, 180.0),
                "External" to Rect(10.0, 20.0, 100.0, 60.0),
                "Task" to Rect(400.0, 80.0, 100.0, 80.0),
                "Task2" to Rect(300.0, 80.0, 100.0, 80.0),
            ),
            mutableMapOf("Message" to Rect(0.0, 0.0, 90.0, 20.0)),
            mutableMapOf("Message" to listOf(Point(0.0, 0.0), Point(1.0, 1.0), Point(2.0, 2.0))),
            mutableSetOf(),
        )
    }

    private fun blackBoxXml(source: String, target: String) = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C">
    <bpmn:participant id="Internal" processRef="P"/><bpmn:participant id="External"/>
    <bpmn:messageFlow id="Message" name="Request" sourceRef="$source" targetRef="$target"/>
    <bpmn:messageFlow id="Other" sourceRef="Task" targetRef="Task2"/>
  </bpmn:collaboration>
  <bpmn:process id="P"><bpmn:serviceTask id="Task"/><bpmn:serviceTask id="Task2"/></bpmn:process>
</bpmn:definitions>"""

    private fun node(
        parent: org.eclipse.elk.graph.ElkNode,
        id: String,
        bounds: Rect,
    ) = ElkGraphUtil.createNode(parent).apply {
        identifier = id
        x = bounds.x
        y = bounds.y
        width = bounds.w
        height = bounds.h
    }

    private companion object {
        const val BLACK_BOX_COLLABORATION_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C"><bpmn:participant id="Participant_internal" processRef="P"/><bpmn:participant id="Participant_external" name="External System"/></bpmn:collaboration>
  <bpmn:process id="P"/>
</bpmn:definitions>"""

        const val LANED_SUBPROCESS_WITH_BOUNDARY_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C"><bpmn:participant id="Participant_1" name="Participant" processRef="P"/></bpmn:collaboration>
  <bpmn:process id="P"><bpmn:laneSet id="LS"><bpmn:lane id="Lane_1" name="Lane"><bpmn:flowNodeRef>SubProcess_1</bpmn:flowNodeRef><bpmn:flowNodeRef>Task_handler</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>
    <bpmn:subProcess id="SubProcess_1"><bpmn:serviceTask id="Task_child"/></bpmn:subProcess>
    <bpmn:serviceTask id="Task_handler"/><bpmn:boundaryEvent id="Boundary_1" attachedToRef="SubProcess_1"/><bpmn:sequenceFlow id="Flow_exception" sourceRef="Boundary_1" targetRef="Task_handler"/>
  </bpmn:process>
</bpmn:definitions>"""

        const val TWO_POOL_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C">
    <bpmn:participant id="A" name="A" processRef="Process_A"/>
    <bpmn:participant id="B" name="B" processRef="Process_B"/>
    <bpmn:messageFlow id="Msg_1" name="Ping" sourceRef="Task_b" targetRef="Task_a"/>
  </bpmn:collaboration>
  <bpmn:process id="Process_A">
    <bpmn:userTask id="Task_a"/>
    <bpmn:userTask id="Task_a2"/>
    <bpmn:sequenceFlow id="Flow_a" sourceRef="Task_a" targetRef="Task_a2"/>
  </bpmn:process>
  <bpmn:process id="Process_B">
    <bpmn:userTask id="Task_b"/>
  </bpmn:process>
</bpmn:definitions>"""

        const val SOLO_COLLABORATION_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C"><bpmn:participant id="Solo" name="Solo" processRef="P"/></bpmn:collaboration>
  <bpmn:process id="P"><bpmn:userTask id="T"/></bpmn:process>
</bpmn:definitions>"""

        const val TWO_MODELED_POOLS_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C">
    <bpmn:participant id="Internal" processRef="P"/><bpmn:participant id="Internal2" processRef="P2"/><bpmn:participant id="External"/>
    <bpmn:messageFlow id="Message" name="Request" sourceRef="Task" targetRef="External"/>
  </bpmn:collaboration>
  <bpmn:process id="P"><bpmn:serviceTask id="Task"/></bpmn:process>
  <bpmn:process id="P2"><bpmn:serviceTask id="Task2"/></bpmn:process>
</bpmn:definitions>"""
    }
}
