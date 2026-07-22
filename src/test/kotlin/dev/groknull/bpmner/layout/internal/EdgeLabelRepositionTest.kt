/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.placement.EdgeLabelReposition
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdgeLabelRepositionTest {

    @Test
    fun `centres a label at the halfway arc-length point, not the endpoint midpoint`() {
        // An asymmetric "over the top" route: a long low approach, a short high departure —
        // the endpoint midpoint (447, 102) lands off this polyline entirely.
        val ctx = context(
            "Flow" to listOf(
                Point(530.0126953125, 109.5),
                Point(530.0126953125, 57.0),
                Point(364.0, 57.0),
                Point(364.0, 94.5),
            ),
        )

        EdgeLabelReposition.reposition("Flow", "retry", ctx)

        val label = ctx.labels.getValue("Flow")
        val centre = Point(label.x + label.w / 2.0, label.y + label.h / 2.0)
        // The true arc-length midpoint sits on the middle (top) segment, at y = 57.
        assertEquals(57.0, centre.y, 0.01)
        assertTrue(centre.x in 364.0..530.0126953125, "centre.x (${centre.x}) must lie on the route's x-range")
    }

    @Test
    fun `centres a label on a straight two-point route at its simple midpoint`() {
        val ctx = context("Flow" to listOf(Point(100.0, 200.0), Point(300.0, 200.0)))

        EdgeLabelReposition.reposition("Flow", "Yes", ctx)

        val label = ctx.labels.getValue("Flow")
        assertEquals(200.0, label.y + label.h / 2.0, 0.01)
        assertEquals(200.0, label.x + label.w / 2.0, 0.01)
    }

    @Test
    fun `does nothing for an unnamed flow`() {
        val ctx = context("Flow" to listOf(Point(0.0, 0.0), Point(10.0, 0.0)))

        EdgeLabelReposition.reposition("Flow", null, ctx)

        assertNull(ctx.labels["Flow"])
    }

    @Test
    fun `does nothing when the flow has no route yet`() {
        val ctx = context()

        EdgeLabelReposition.reposition("Flow", "Yes", ctx)

        assertNull(ctx.labels["Flow"])
    }

    private fun context(vararg edges: Pair<String, List<Point>>): PlacementContext = PlacementContext(
        PlacementTestSkeletons.parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P"/>
</bpmn:definitions>""",
        ),
        PlacementTestSkeletons.skeleton(ElkGraphUtil.createGraph(), emptyMap()),
        mutableMapOf(),
        mutableMapOf(),
        edges.toMap().toMutableMap(),
        mutableSetOf(),
    )
}
