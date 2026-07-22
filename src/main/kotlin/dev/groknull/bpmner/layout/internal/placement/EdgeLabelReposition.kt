/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import kotlin.math.hypot

/**
 * Shared edge-label centring for post-ELK route recomputation.
 *
 * Every placement step that discards or shifts an ELK-produced route (loop-back arcs, exception
 * routes, lane/pool-translated sequence flows) must also reposition that flow's label — an ELK
 * label position is only valid for the ELK section it was computed from, and goes stale (or, for a
 * flow ELK never routed at all, such as a loop-back edge, never exists) the moment a placement
 * processor replaces that section.
 */
internal object EdgeLabelReposition {

    /** Centres [id]'s label on its current [PlacementContext.edges] route, if it has one and a name. */
    fun reposition(id: String, name: String?, ctx: PlacementContext) {
        if (name.isNullOrBlank()) return
        val route = ctx.edges[id] ?: return
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions(name, BpmnPlacementPass.EDGE_LABEL_WIDTH)
        val mid = pathMidpoint(route)
        ctx.labels[id] = Rect(mid.x - width / 2.0, mid.y - height / 2.0, width, height)
    }

    /**
     * The point halfway along [route] by cumulative segment length (not the midpoint of its first
     * and last waypoints, which for a route with an unequal bend on each side — e.g. an
     * over/around arc that isn't a symmetric "Z" — can land off the polyline entirely).
     */
    private fun pathMidpoint(route: List<Point>): Point {
        if (route.size <= 1) return route.first()
        val segments = route.zipWithNext()
        val lengths = segments.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        val total = lengths.sum()
        if (total <= 0.0) return route.first()
        var remaining = total / 2.0
        segments.forEachIndexed { i, (a, b) ->
            val len = lengths[i]
            if (remaining <= len) {
                val t = if (len > 0.0) remaining / len else 0.0
                return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            remaining -= len
        }
        return route.last()
    }
}
