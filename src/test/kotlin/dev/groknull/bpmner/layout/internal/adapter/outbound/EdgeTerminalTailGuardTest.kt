/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.EdgeTerminalTailGuard
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [EdgeTerminalTailGuard].
 *
 * Builds a minimal [PlacementContext] with hand-crafted edge waypoints and asserts the
 * processor's postconditions without running the full ELK pipeline.
 */
class EdgeTerminalTailGuardTest {

    private val flatModel = PlacementTestSkeletons.parse(
        """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D1" targetNamespace="https://test">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:serviceTask id="A"/>
    <bpmn:serviceTask id="B"/>
    <bpmn:sequenceFlow id="F1" sourceRef="A" targetRef="B"/>
  </bpmn:process>
</bpmn:definitions>""",
    )

    private fun contextWithEdge(waypoints: List<Point>, shapes: Map<String, Rect> = emptyMap()): PlacementContext {
        val root = ElkGraphUtil.createGraph()
        return PlacementContext(
            model = flatModel,
            skeleton = PlacementTestSkeletons.skeleton(root, emptyMap()),
            shapes = shapes.toMutableMap(),
            labels = mutableMapOf(),
            edges = mutableMapOf("F1" to waypoints),
            expanded = mutableSetOf(),
        )
    }

    @Test
    fun `too-short horizontal final segment slides the source exit point when it has room`() {
        // Mirrors boundary-error-task's Flow_rejoin: A's top-centre exit (318) is only 10px from
        // B's left-edge entry (328) in X. A spans x=[268,368], so sliding the exit to x=308 fits.
        val ctx = contextWithEdge(
            waypoints = listOf(Point(318.0, 258.0), Point(318.0, 161.0), Point(328.0, 161.0)),
            shapes = mapOf(
                "A" to Rect(268.0, 258.0, 100.0, 80.0),
                "B" to Rect(328.0, 121.0, 100.0, 80.0),
            ),
        )

        EdgeTerminalTailGuard.process(ctx)

        val wps = ctx.edges.getValue("F1")
        assertEquals(
            listOf(Point(308.0, 258.0), Point(308.0, 161.0), Point(328.0, 161.0)),
            wps,
            "Must stay a simple monotonic L with the exit point slid, not a backtracking jog",
        )
        assertEquals(Point(328.0, 161.0), wps.last(), "Target entry point must not move")
        assertTrue(kotlin.math.abs(wps[2].x - wps[1].x) >= 20.0, "Final segment must reach the minimum tail")
    }

    @Test
    fun `too-short horizontal final segment falls back to a jog when the source has no room to slide`() {
        // Source A is only 15px wide (x=[310,325]) — too narrow to slide the exit point far
        // enough left to reach the minimum tail, so the guard must fall back to the mid-course jog
        // rather than clamp the exit outside A's own boundary.
        val ctx = contextWithEdge(
            waypoints = listOf(Point(318.0, 258.0), Point(318.0, 161.0), Point(328.0, 161.0)),
            shapes = mapOf(
                "A" to Rect(310.0, 258.0, 15.0, 80.0),
                "B" to Rect(328.0, 121.0, 100.0, 80.0),
            ),
        )

        EdgeTerminalTailGuard.process(ctx)

        val wps = ctx.edges.getValue("F1")
        assertEquals(5, wps.size, "Must fall back to the 5-point jog when the source has no room")
        assertEquals(Point(318.0, 258.0), wps.first(), "Fallback must not move the source exit point")
        assertEquals(Point(328.0, 161.0), wps.last(), "Target entry point must not move")
        val finalLen = kotlin.math.abs(wps[4].x - wps[3].x)
        assertTrue(finalLen >= 20.0, "Final segment must be >= the minimum tail, was $finalLen")
    }

    @Test
    fun `too-short horizontal final segment falls back to a jog when shape info is unavailable`() {
        val ctx = contextWithEdge(listOf(Point(318.0, 258.0), Point(318.0, 161.0), Point(328.0, 161.0)))

        EdgeTerminalTailGuard.process(ctx)

        val wps = ctx.edges.getValue("F1")
        assertEquals(5, wps.size, "Too-short L must fall back to a 5-point detour without shape info")
        assertEquals(Point(318.0, 258.0), wps.first(), "Source exit point must not move")
        assertEquals(Point(328.0, 161.0), wps.last(), "Target entry point must not move")
        val finalLen = kotlin.math.abs(wps[4].x - wps[3].x)
        assertTrue(finalLen >= 20.0, "Final segment must be >= the minimum tail, was $finalLen")
        // Every segment must stay axis-aligned.
        for (i in 1 until wps.size) {
            val a = wps[i - 1]
            val b = wps[i]
            assertTrue(a.x == b.x || a.y == b.y, "Segment $a→$b must be axis-aligned")
        }
    }

    @Test
    fun `too-short vertical final segment slides the source exit point when it has room`() {
        val ctx = contextWithEdge(
            waypoints = listOf(Point(100.0, 100.0), Point(250.0, 100.0), Point(250.0, 108.0)),
            shapes = mapOf(
                "A" to Rect(50.0, 60.0, 100.0, 40.0),
                "B" to Rect(200.0, 108.0, 100.0, 80.0),
            ),
        )

        EdgeTerminalTailGuard.process(ctx)

        val wps = ctx.edges.getValue("F1")
        assertEquals(Point(250.0, 108.0), wps.last(), "Target entry point must not move")
        val finalLen = kotlin.math.abs(wps.last().y - wps[wps.size - 2].y)
        assertTrue(finalLen >= 20.0, "Final segment must be >= the minimum tail, was $finalLen")
    }

    @Test
    fun `too-short vertical final segment falls back to a jog when shape info is unavailable`() {
        val ctx = contextWithEdge(listOf(Point(100.0, 100.0), Point(250.0, 100.0), Point(250.0, 108.0)))

        EdgeTerminalTailGuard.process(ctx)

        val wps = ctx.edges.getValue("F1")
        assertEquals(5, wps.size)
        assertEquals(Point(100.0, 100.0), wps.first())
        assertEquals(Point(250.0, 108.0), wps.last())
        val finalLen = kotlin.math.abs(wps[4].y - wps[3].y)
        assertTrue(finalLen >= 20.0, "Final segment must be >= the minimum tail, was $finalLen")
    }

    @Test
    fun `simple L with an already-comfortable final segment is left untouched`() {
        val original = listOf(Point(48.0, 192.0), Point(48.0, 312.0), Point(268.0, 312.0))
        val ctx = contextWithEdge(original)

        EdgeTerminalTailGuard.process(ctx)

        assertEquals(original, ctx.edges.getValue("F1"), "A comfortable final segment must not be rewritten")
    }

    @Test
    fun `straight two-point edge is left untouched`() {
        val original = listOf(Point(48.0, 192.0), Point(138.0, 192.0))
        val ctx = contextWithEdge(original)

        EdgeTerminalTailGuard.process(ctx)

        assertEquals(original, ctx.edges.getValue("F1"), "A straight line has no elbow to fix")
    }

    @Test
    fun `bespoke multi-bend arc (4+ waypoints) is left untouched regardless of final segment length`() {
        // Mirrors a loop-back/exception arc shape — out of this guard's scope.
        val original = listOf(Point(396.0, 63.0), Point(396.0, 20.0), Point(231.0, 20.0), Point(231.0, 25.0))
        val ctx = contextWithEdge(original)

        EdgeTerminalTailGuard.process(ctx)

        assertEquals(original, ctx.edges.getValue("F1"), "Only the plain 3-waypoint L is in scope")
    }
}
