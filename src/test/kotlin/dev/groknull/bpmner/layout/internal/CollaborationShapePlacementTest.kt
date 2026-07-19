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
                ),
            ),
            mutableMapOf("Start_1" to Rect(60.0, 50.0, 36.0, 36.0)),
            mutableMapOf(),
            mutableMapOf(),
            mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        assertEquals(Rect(10.0, 20.0, 500.0, 240.0), ctx.shapes["Participant_1"])
        assertEquals(Rect(10.0, 20.0, 500.0, 80.0), ctx.shapes["Lane_sales"])
        assertEquals(Rect(10.0, 100.0, 500.0, 80.0), ctx.shapes["Lane_warehouse"])
        assertEquals(Rect(10.0, 180.0, 500.0, 80.0), ctx.shapes["Lane_delivery"])
        assertEquals(Rect(60.0, 40.0, 36.0, 36.0), ctx.shapes["Start_1"])
        assertEquals(MoveRecord("CollaborationShapePlacement", 0.0, -10.0), ctx.moves["Start_1"])
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
}
