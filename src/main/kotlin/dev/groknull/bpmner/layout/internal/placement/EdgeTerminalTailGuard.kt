/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import kotlin.math.abs

/**
 * Bespoke edge: guards against a too-short terminal segment on any routed edge.
 *
 * ELK's native orthogonal router (and, in principle, any other routing step) can produce a
 * simple two-segment "L" path whose final segment — the one leading into the target, ending at
 * the arrowhead — is only a few pixels long, purely because the source's exit port and the
 * target's entry port happen to be close together on the final segment's axis. A short final
 * segment makes the bend and the arrowhead visually collide, reading as a stray kink rather than
 * a deliberate turn.
 *
 * The fix slides the source's exit point a few pixels further along its own node's boundary edge
 * (still a valid port on that node, just not exactly where ELK first placed it) so the path stays
 * a simple, monotonic two-segment "L". Only the source side can be slid this way: the exit edge
 * is perpendicular to the final segment, so moving along it changes the coordinate the final
 * segment's length depends on; the target's entry point sits on an edge parallel to the final
 * segment's approach, so sliding along *that* edge only changes where on the boundary it lands,
 * not the tail length. An earlier version instead inserted a mid-course jog when the two ports
 * were close together, but that jog necessarily backtracks (moves away from the target before
 * turning back), reading as a kink of its own — sliding the source port is a strictly smaller,
 * monotonic change. Falls back to that jog only if the source has no room to slide (e.g.
 * `ctx.shapes` lacks the node, or the node is pathologically small).
 *
 * Runs last among edge-producing steps (after [SequenceEdgeElkCopy], [CollaborationShapePlacement],
 * before ELK label coordinates are projected into [PlacementContext.labels]),
 * so it sees every edge's final geometry regardless of which processor produced it. Only a plain
 * 3-waypoint orthogonal "L" on a [SequenceFlow] is touched — bespoke multi-bend arcs
 * ([LoopBackEdgeArcs], [ExceptionEdgeRoutes]) already carry their own minimum-tail guarantees and
 * are left alone.
 */
internal object EdgeTerminalTailGuard : PlacementProcessor {

    /** Minimum straight length of an edge's final segment, before it enters its target. */
    private const val MIN_TERMINAL_TAIL = 20.0

    /** Waypoint count of the simple orthogonal "L" this guard is scoped to. */
    private const val SIMPLE_L_WAYPOINT_COUNT = 3

    override fun process(ctx: PlacementContext) {
        val flowsById = ctx.model.getModelElementsByType(SequenceFlow::class.java).associateBy { it.id }
        ctx.edges.toMap().forEach { (flowId, waypoints) ->
            val sf = flowsById[flowId] ?: return@forEach
            val fixed = extendedTailOrNull(waypoints, sf, ctx) ?: return@forEach
            ctx.edges[flowId] = fixed
        }
    }

    /** Returns re-routed waypoints if [waypoints] is a too-short-tailed simple L, else null. */
    private fun extendedTailOrNull(waypoints: List<Point>, sf: SequenceFlow, ctx: PlacementContext): List<Point>? {
        if (waypoints.size != SIMPLE_L_WAYPOINT_COUNT) return null
        val (start, elbow, entry) = waypoints

        val finalVertical = elbow.x == entry.x
        val finalHorizontal = elbow.y == entry.y
        if (finalVertical == finalHorizontal) return null // not axis-aligned (or degenerate); leave alone

        val finalLength = if (finalVertical) abs(entry.y - elbow.y) else abs(entry.x - elbow.x)
        if (finalLength >= MIN_TERMINAL_TAIL) return null

        // Only a genuine orthogonal L — first segment perpendicular to the final one — is safe
        // to re-route; anything else is left untouched rather than guessed at.
        val firstVertical = start.x == elbow.x
        val firstHorizontal = start.y == elbow.y
        val sourceRect = ctx.shapes[sf.source?.id]
        return when {
            finalHorizontal && firstVertical -> reshapeVerticalExit(start, entry, sourceRect)
            finalVertical && firstHorizontal -> reshapeHorizontalExit(start, entry, sourceRect)
            else -> null
        }
    }

    /**
     * [start] exits vertically (a node's top/bottom edge); [entry] is approached horizontally
     * (a node's left/right edge, so [entry]'s X is pinned to that boundary and cannot slide
     * without leaving it). Slides [start]'s X along the source's own top/bottom span so the path
     * stays a simple monotonic L; falls back to a jog only if the source has no room to slide.
     */
    private fun reshapeVerticalExit(start: Point, entry: Point, sourceRect: Rect?): List<Point> {
        val approachSign = if (entry.x >= start.x) -1.0 else 1.0
        val slidStartX = entry.x + approachSign * MIN_TERMINAL_TAIL
        if (sourceRect != null && slidStartX in sourceRect.x..(sourceRect.x + sourceRect.w)) {
            return listOf(Point(slidStartX, start.y), Point(slidStartX, entry.y), entry)
        }
        return detourVerticalExit(start, entry)
    }

    /**
     * [start] exits horizontally (a node's left/right edge); [entry] is approached vertically
     * (a node's top/bottom edge, pinned the same way). Mirrors [reshapeVerticalExit] on the
     * perpendicular axis.
     */
    private fun reshapeHorizontalExit(start: Point, entry: Point, sourceRect: Rect?): List<Point> {
        val approachSign = if (entry.y >= start.y) -1.0 else 1.0
        val slidStartY = entry.y + approachSign * MIN_TERMINAL_TAIL
        if (sourceRect != null && slidStartY in sourceRect.y..(sourceRect.y + sourceRect.h)) {
            return listOf(Point(start.x, slidStartY), Point(entry.x, slidStartY), entry)
        }
        return detourHorizontalExit(start, entry)
    }

    /**
     * Fallback: [start] exits vertically, [entry] is approached horizontally, and neither port
     * had room to slide. Detours through a mid-height horizontal jog so the final horizontal run
     * reaches [MIN_TERMINAL_TAIL], without altering either fixed port position.
     */
    private fun detourVerticalExit(start: Point, entry: Point): List<Point> {
        val midY = (start.y + entry.y) / 2.0
        val approachSign = if (entry.x >= start.x) -1.0 else 1.0
        val midX = entry.x + approachSign * MIN_TERMINAL_TAIL
        return listOf(start, Point(start.x, midY), Point(midX, midY), Point(midX, entry.y), entry)
    }

    /**
     * Fallback: [start] exits horizontally, [entry] is approached vertically, and neither port
     * had room to slide. Detours through a mid-width vertical jog so the final vertical run
     * reaches [MIN_TERMINAL_TAIL], without altering either fixed port position.
     */
    private fun detourHorizontalExit(start: Point, entry: Point): List<Point> {
        val midX = (start.x + entry.x) / 2.0
        val approachSign = if (entry.y >= start.y) -1.0 else 1.0
        val midY = entry.y + approachSign * MIN_TERMINAL_TAIL
        return listOf(start, Point(midX, start.y), Point(midX, midY), Point(entry.x, midY), entry)
    }
}
