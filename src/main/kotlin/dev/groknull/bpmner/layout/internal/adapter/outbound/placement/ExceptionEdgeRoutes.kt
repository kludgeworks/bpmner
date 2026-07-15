/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Bespoke edge: deterministic three-point exception edge routes.
 *
 * Every sequence flow whose source is a [BoundaryEvent] has a deterministic
 * orthogonal route in [PlacementContext.edges]:
 * - Normal case (handler below or beside): (bCx, bBottom) → (bCx, handlerCy) → (handlerEdge, handlerCy).
 * - Detour case (straight vertical would pass through the host): five-point route around host right edge.
 *
 * No obstacle detection — the geometry is deterministic from placed boundary shape and handler shape bounds.
 *
 * See: AD-557-12
 */
internal object ExceptionEdgeRoutes : PlacementProcessor {

    /** Gap below boundary bottom and right of host when detouring around a host node. */
    internal const val EXCEPTION_DETOUR_GAP = 20.0

    override fun process(ctx: PlacementContext) {
        val boundaryEvents = ctx.model.getModelElementsByType(BoundaryEvent::class.java)
            .associateBy { it.id }

        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id in boundaryEvents }
            .sortedBy { it.id }
            .forEach { sf ->
                val be = boundaryEvents[sf.source?.id] ?: return@forEach
                val bRect = ctx.shapes[be.id] ?: return@forEach
                val tRect = ctx.shapes[sf.target?.id ?: return@forEach] ?: return@forEach
                val hostRect = ctx.shapes[be.attachedTo?.id]
                ctx.edges[sf.id] = routeExceptionEdge(bRect, tRect, hostRect)
            }
    }

    /**
     * Computes waypoints for one exception edge from placed boundary [bRect] to handler [tRect].
     *
     * If the straight vertical at the boundary centre-X would pass through the host, a five-point
     * detour routes around the host's right edge. Otherwise a three-point drop-then-horizontal.
     */
    internal fun routeExceptionEdge(bRect: Rect, tRect: Rect, hostRect: Rect?): List<Point> {
        val startX = bRect.x + bRect.w / 2.0
        val startY = bRect.y + bRect.h
        val targetCy = tRect.y + tRect.h / 2.0
        val enterX = if (tRect.x >= startX) tRect.x else tRect.x + tRect.w

        val vertMinY = minOf(startY, targetCy)
        val vertMaxY = maxOf(startY, targetCy)
        val pathClearsHost = hostRect == null ||
            startX < hostRect.x ||
            startX > hostRect.x + hostRect.w ||
            vertMaxY <= hostRect.y ||
            vertMinY >= hostRect.y + hostRect.h

        if (!pathClearsHost) {
            val h = hostRect ?: return listOf(
                Point(startX, startY),
                Point(startX, targetCy),
                Point(enterX, targetCy),
            )
            val clearY = startY + EXCEPTION_DETOUR_GAP
            val clearX = h.x + h.w + EXCEPTION_DETOUR_GAP
            return listOf(
                Point(startX, startY),
                Point(startX, clearY),
                Point(clearX, clearY),
                Point(clearX, targetCy),
                Point(enterX, targetCy),
            )
        }

        return listOf(
            Point(startX, startY),
            Point(startX, targetCy),
            Point(enterX, targetCy),
        )
    }
}
