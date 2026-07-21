/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.MoveRecord
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import dev.groknull.bpmner.layout.internal.placement.WhiteBoxPoolBandPlacement
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WhiteBoxPoolBandPlacementTest {

    @Test
    fun `stacks two white-box pools into a shared full-width band and ledgers the move`() {
        val ctx = twoPoolContext()

        WhiteBoxPoolBandPlacement.process(ctx)

        assertEquals(Rect(10.0, 20.0, 400.0, 200.0), ctx.shapes["A"])
        assertEquals(Rect(10.0, 300.0, 400.0, 180.0), ctx.shapes["B"])
        assertEquals(MoveRecord("WhiteBoxPoolBandPlacement", -490.0, 280.0), ctx.moves["B"])
        assertEquals(MoveRecord("WhiteBoxPoolBandPlacement", -490.0, 280.0), ctx.moves["Task_b"])
    }

    @Test
    fun `translates descendants without changing their position relative to their own pool`() {
        val ctx = twoPoolContext()

        WhiteBoxPoolBandPlacement.process(ctx)

        val pool = ctx.shapes.getValue("B")
        val task = ctx.shapes.getValue("Task_b")
        assertEquals(60.0 to 40.0, (task.x - pool.x) to (task.y - pool.y))
    }

    @Test
    fun `routes a cross-pool message flow between the facing edges with a midpoint label`() {
        val ctx = twoPoolContext()

        WhiteBoxPoolBandPlacement.process(ctx)

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

        WhiteBoxPoolBandPlacement.process(ctx)

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

        WhiteBoxPoolBandPlacement.process(ctx)

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

    private companion object {
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
    }
}
