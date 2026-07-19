/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.CollaborationShapePlacement
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CollaborationShapePlacementTest {

    @Test
    fun `projects ELK participant and lane bounds without relocating members`() {
        val model = PlacementTestSkeletons.parse(BpmnToElkMapperTest.COLLABORATION_LANES_XML)
        val root = ElkGraphUtil.createGraph()
        val participant = ElkGraphUtil.createNode(root).apply {
            identifier = "Participant_1"
            x = 10.0
            y = 20.0
            width = 500.0
            height = 300.0
        }
        val sales = ElkGraphUtil.createNode(participant).apply {
            identifier = "Lane_sales"
            x = 30.0
            y = 10.0
            width = 400.0
            height = 80.0
        }
        val warehouse = ElkGraphUtil.createNode(participant).apply {
            identifier = "Lane_warehouse"
            x = 30.0
            y = 100.0
            width = 400.0
            height = 80.0
        }
        val delivery = ElkGraphUtil.createNode(participant).apply {
            identifier = "Lane_delivery"
            x = 30.0
            y = 190.0
            width = 400.0
            height = 80.0
        }
        val ctx = PlacementContext(
            model,
            PlacementTestSkeletons.skeleton(
                root,
                mapOf(
                    "Participant_1" to participant,
                    "Lane_sales" to sales,
                    "Lane_warehouse" to warehouse,
                    "Lane_delivery" to delivery,
                ),
            ),
            mutableMapOf("Start_1" to Rect(50.0, 60.0, 36.0, 36.0)),
            mutableMapOf(),
            mutableMapOf(),
            mutableSetOf(),
        )

        CollaborationShapePlacement.process(ctx)

        assertEquals(Rect(10.0, 20.0, 500.0, 300.0), ctx.shapes["Participant_1"])
        assertEquals(Rect(40.0, 30.0, 400.0, 80.0), ctx.shapes["Lane_sales"])
        assertEquals(Rect(50.0, 60.0, 36.0, 36.0), ctx.shapes["Start_1"])
    }
}
