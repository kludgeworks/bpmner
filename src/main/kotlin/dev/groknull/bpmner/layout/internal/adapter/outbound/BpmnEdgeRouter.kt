/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkSkeleton
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

private typealias Rect = BpmnPlacementPass.Rect
private typealias Point = BpmnPlacementPass.Point

/**
 * Phase 2 edge utilities: ELK section copy for sequence flows and association routing.
 *
 * After the AD-557-12 collapse this object owns exactly two operations:
 * 1. [placeSequenceEdgeWaypoints] — copy ELK routing sections verbatim for ordinary flows.
 * 2. [placeAssociationEdges] — straight border-to-border connectors for associations.
 *
 * Exception edges are now owned by [BpmnPlacementPass.routeExceptionEdges] (the ONE sanctioned
 * bespoke edge op). All repair functions that existed because phase-2 node moves invalidated
 * ELK's routes have been deleted (AD-557-11/AD-557-12 — no node moves, no repairs).
 */
internal object BpmnEdgeRouter {

    // ── ELK section copy ─────────────────────────────────────────────────────

    /**
     * Copies ELK routing sections as waypoints for all ordinary (non-boundary-source)
     * sequence flows not already placed by [BpmnPlacementPass.routeExceptionEdges].
     *
     * ELK edge coordinates are relative to the edge's containing (LCA) node.
     * [BpmnPlacementPass.edgeContainerOffset] converts them to canvas-absolute.
     */
    internal fun placeSequenceEdgeWaypoints(
        model: BpmnModelInstance,
        skeleton: ElkSkeleton,
        edges: MutableMap<String, List<Point>>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filter { it.source?.id !in boundaryIds && it.id !in edges }
            .sortedBy { it.id }
            .forEach { sf ->
                val elkEdge = skeleton.edgeMap[sf.id] ?: return@forEach
                val section = elkEdge.sections.firstOrNull() ?: return@forEach
                val (ox, oy) = BpmnPlacementPass.edgeContainerOffset(elkEdge)
                val waypoints = mutableListOf(Point(section.startX + ox, section.startY + oy))
                section.bendPoints.mapTo(waypoints) { Point(it.x + ox, it.y + oy) }
                waypoints.add(Point(section.endX + ox, section.endY + oy))
                edges[sf.id] = waypoints
            }
    }

    // ── Association routing ──────────────────────────────────────────────────

    /**
     * Places association edges as straight lines between shape borders,
     * using the nearest facing border point so connectors attach to shape edges.
     */
    internal fun placeAssociationEdges(
        model: BpmnModelInstance,
        shapes: Map<String, Rect>,
        edges: MutableMap<String, List<Point>>,
    ) {
        model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }
            .forEach { assoc ->
                val sourceRect = shapes[assoc.source?.id] ?: return@forEach
                val targetRect = shapes[assoc.target?.id] ?: return@forEach
                edges[assoc.id] = listOf(
                    borderPoint(sourceRect, targetRect),
                    borderPoint(targetRect, sourceRect),
                )
            }
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    /** The point halfway along the total length of a polyline. */
    internal fun polylineMidpoint(wps: List<Point>): Point {
        val total = (1 until wps.size).sumOf { i -> dist(wps[i - 1], wps[i]) }
        if (total == 0.0) return wps.first()
        var remaining = total / 2.0
        for (i in 1 until wps.size) {
            val seg = dist(wps[i - 1], wps[i])
            if (remaining <= seg) {
                val t = if (seg == 0.0) 0.0 else remaining / seg
                return Point(
                    wps[i - 1].x + (wps[i].x - wps[i - 1].x) * t,
                    wps[i - 1].y + (wps[i].y - wps[i - 1].y) * t,
                )
            }
            remaining -= seg
        }
        return wps.last()
    }

    internal fun dist(a: Point, b: Point): Double = kotlin.math.hypot(b.x - a.x, b.y - a.y)

    /**
     * Returns the point on [rect]'s border that faces [toward] — the intersection of the border
     * with the line from [rect]'s centre to [toward]'s centre.
     */
    private fun borderPoint(rect: Rect, toward: Rect): Point {
        val cx = rect.x + rect.w / 2.0
        val cy = rect.y + rect.h / 2.0
        val tx = toward.x + toward.w / 2.0
        val ty = toward.y + toward.h / 2.0
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0.0 && dy == 0.0) return Point(cx, cy)
        val hw = rect.w / 2.0
        val hh = rect.h / 2.0
        val scaleX = if (dx != 0.0) hw / kotlin.math.abs(dx) else Double.MAX_VALUE
        val scaleY = if (dy != 0.0) hh / kotlin.math.abs(dy) else Double.MAX_VALUE
        val scale = kotlin.math.min(scaleX, scaleY)
        return Point(cx + dx * scale, cy + dy * scale)
    }
}
