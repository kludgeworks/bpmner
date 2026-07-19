/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.CollaborationShapePlacement
import dev.groknull.bpmner.layout.internal.placement.MoveRecord
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CollaborationShapePlacementTest {

    @Test
    fun `projects ordered full-width lane bands and ledgers member translations`() {
        val model = PlacementTestSkeletons.parse(BpmnToElkMapperTest.COLLABORATION_LANES_XML)
        val root = ElkGraphUtil.createGraph()
        val participant = node(root, "Participant_1", Rect(10.0, 20.0, 500.0, 300.0))
        val sales = node(participant, "Lane_sales", Rect(30.0, 10.0, 400.0, 80.0))
        val warehouse = node(participant, "Lane_warehouse", Rect(30.0, 100.0, 400.0, 80.0))
        val delivery = node(participant, "Lane_delivery", Rect(30.0, 190.0, 400.0, 80.0))
        val start = node(sales, "Start_1", Rect(20.0, 20.0, 36.0, 36.0))
        val pick = node(warehouse, "Task_pick", Rect(20.0, 20.0, 100.0, 80.0))
        val ctx = PlacementContext(
            model,
            PlacementTestSkeletons.skeleton(
                root,
                mapOf(
                    "Participant_1" to participant,
                    "Lane_sales" to sales,
                    "Lane_warehouse" to warehouse,
                    "Lane_delivery" to delivery,
                    "Start_1" to start,
                    "Task_pick" to pick,
                ),
            ),
            mutableMapOf(
                "Start_1" to Rect(60.0, 50.0, 36.0, 36.0),
                "Task_pick" to Rect(60.0, 140.0, 100.0, 80.0),
            ),
            mutableMapOf(),
            mutableMapOf(),
            mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        assertEquals(Rect(10.0, 20.0, 500.0, 240.0), ctx.shapes["Participant_1"])
        assertEquals(Rect(40.0, 20.0, 470.0, 80.0), ctx.shapes["Lane_sales"])
        assertEquals(Rect(40.0, 100.0, 470.0, 80.0), ctx.shapes["Lane_warehouse"])
        assertEquals(Rect(40.0, 180.0, 470.0, 80.0), ctx.shapes["Lane_delivery"])
        assertEquals(Rect(10.0, 20.0, 30.0, 240.0), ctx.labels["Participant_1"])
        assertEquals(Rect(40.0, 20.0, 30.0, 80.0), ctx.labels["Lane_sales"])
        assertEquals(Rect(90.0, 40.0, 36.0, 36.0), ctx.shapes["Start_1"])
        assertEquals(MoveRecord("CollaborationShapePlacement", 30.0, -10.0), ctx.moves["Start_1"])
        assertEquals(Rect(90.0, 120.0, 100.0, 80.0), ctx.shapes["Task_pick"])
        assertEquals(
            listOf(
                BpmnPlacementPass.Point(108.0, 76.0),
                BpmnPlacementPass.Point(108.0, 98.0),
                BpmnPlacementPass.Point(140.0, 98.0),
                BpmnPlacementPass.Point(140.0, 120.0),
            ),
            ctx.edges["Flow_1"],
        )
    }

    @Test
    fun `translates lane subprocess descendants boundary attachments and their route`() {
        val model = PlacementTestSkeletons.parse(LANED_SUBPROCESS_WITH_BOUNDARY_XML)
        val root = ElkGraphUtil.createGraph()
        val participant = node(root, "Participant_1", Rect(10.0, 20.0, 500.0, 300.0))
        val lane = node(participant, "Lane_1", Rect(30.0, 10.0, 400.0, 80.0))
        val subprocess = node(lane, "SubProcess_1", Rect(20.0, 20.0, 200.0, 100.0))
        val child = node(subprocess, "Task_child", Rect(10.0, 10.0, 100.0, 80.0))
        val handler = node(lane, "Task_handler", Rect(250.0, 20.0, 100.0, 80.0))
        val boundary = node(root, "Boundary_1", Rect(80.0, 100.0, 36.0, 36.0))
        val ctx = PlacementContext(
            model,
            PlacementTestSkeletons.skeleton(
                root,
                mapOf(
                    "Participant_1" to participant,
                    "Lane_1" to lane,
                    "SubProcess_1" to subprocess,
                    "Task_child" to child,
                    "Task_handler" to handler,
                    "Boundary_1" to boundary,
                ),
            ),
            mutableMapOf(
                "SubProcess_1" to Rect(60.0, 50.0, 200.0, 100.0),
                "Task_child" to Rect(70.0, 60.0, 100.0, 80.0),
                "Task_handler" to Rect(290.0, 50.0, 100.0, 80.0),
                "Boundary_1" to Rect(80.0, 100.0, 36.0, 36.0),
            ),
            mutableMapOf(),
            mutableMapOf("Flow_exception" to listOf(BpmnPlacementPass.Point(98.0, 118.0), BpmnPlacementPass.Point(290.0, 90.0))),
            mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        assertEquals(Rect(100.0, 50.0, 100.0, 80.0), ctx.shapes["Task_child"])
        assertEquals(Rect(110.0, 90.0, 36.0, 36.0), ctx.shapes["Boundary_1"])
        assertEquals(MoveRecord("CollaborationShapePlacement", 30.0, -10.0), ctx.moves["Task_child"])
        assertEquals(MoveRecord("CollaborationShapePlacement", 30.0, -10.0), ctx.moves["Boundary_1"])
        assertEquals(
            listOf(BpmnPlacementPass.Point(128.0, 108.0), BpmnPlacementPass.Point(320.0, 80.0)),
            ctx.edges["Flow_exception"],
        )
    }

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
        const val LANED_SUBPROCESS_WITH_BOUNDARY_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C"><bpmn:participant id="Participant_1" name="Participant" processRef="P"/></bpmn:collaboration>
  <bpmn:process id="P"><bpmn:laneSet id="LS"><bpmn:lane id="Lane_1" name="Lane"><bpmn:flowNodeRef>SubProcess_1</bpmn:flowNodeRef><bpmn:flowNodeRef>Task_handler</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>
    <bpmn:subProcess id="SubProcess_1"><bpmn:serviceTask id="Task_child"/></bpmn:subProcess>
    <bpmn:serviceTask id="Task_handler"/><bpmn:boundaryEvent id="Boundary_1" attachedToRef="SubProcess_1"/><bpmn:sequenceFlow id="Flow_exception" sourceRef="Boundary_1" targetRef="Task_handler"/>
  </bpmn:process>
</bpmn:definitions>"""
    }
}
