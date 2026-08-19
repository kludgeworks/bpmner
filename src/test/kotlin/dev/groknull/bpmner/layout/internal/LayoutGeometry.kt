/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.layout.internal

import org.w3c.dom.Document
import org.w3c.dom.Element

private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"
private const val DD_NS = "http://www.omg.org/spec/DD/20100524/DI"

/**
 * The 26-fixture layout corpus, shared by [GenerateCandidateGoldens][main] (goldens + the Tier-2
 * metrics baseline) and the Tier-2 tripwire test. [ElkGoldenLayoutTest]'s own `@ValueSource`
 * lists must stay in sync with this — JUnit5 annotation arguments must be compile-time constants,
 * so they cannot reference this list directly.
 */
internal val LAYOUT_CORPUS_FIXTURES = listOf(
    "representative-process",
    "explicit-cycle",
    "long-labels",
    "annotation-and-group",
    "data-object-and-store",
    "boundary-timer-task",
    "boundary-on-subprocess",
    "boundary-error-task",
    "boundary-multi",
    "subprocess-flat",
    "subprocess-loop",
    "subprocess-branch",
    "subprocess-nested",
    "subprocess-no-start-cycle",
    "subprocess-sequential-sharing",
    "collab-lanes",
    "collab-two-pools",
    "collab-blackbox",
    "collab-msg-endpoint",
    "collab-msg-label",
    "collab-subprocess",
    "collab-bioc",
    "collab-lanes-loopback",
    "collab-lanes-branch-rejoin",
    "miwg-c2-four-pools",
    "miwg-a2-1",
    "miwg-a3-0",
    "event-subprocess",
    "compensation-handler",
    "boundary-non-interrupting",
    "signal-escalation",
    "compensate",
    "miwg-b2-dense",
)

/** A BPMN DI shape's absolute bounds, keyed by the `bpmnElement` id it renders. */
internal data class DiRect(val id: String, val x: Double, val y: Double, val w: Double, val h: Double) {
    val right get() = x + w
    val bottom get() = y + h
}

/** A single point on a BPMN DI edge's waypoint polyline. */
internal data class DiPoint(val x: Double, val y: Double)

/** One BPMN DI edge's ordered waypoints. */
internal data class DiEdge(val id: String, val waypoints: List<DiPoint>)

/** Every `BPMNShape` with a positive-area `dc:Bounds`, in document order. */
internal fun extractShapeRects(doc: Document): List<DiRect> {
    val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
    return (0 until shapes.length)
        .map { shapes.item(it) as Element }
        .mapNotNull { shape ->
            val id = shape.getAttribute("bpmnElement")
            val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return@mapNotNull null
            val x = bounds.getAttribute("x").toDoubleOrNull() ?: return@mapNotNull null
            val y = bounds.getAttribute("y").toDoubleOrNull() ?: return@mapNotNull null
            val w = bounds.getAttribute("width").toDoubleOrNull() ?: return@mapNotNull null
            val h = bounds.getAttribute("height").toDoubleOrNull() ?: return@mapNotNull null
            if (w <= 0 || h <= 0) return@mapNotNull null
            DiRect(id, x, y, w, h)
        }
}

/** Every `BPMNEdge`'s ordered waypoints, in document order. */
internal fun extractEdges(doc: Document): List<DiEdge> {
    val edges = doc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
    return (0 until edges.length)
        .map { edges.item(it) as Element }
        .map { edge ->
            val id = edge.getAttribute("bpmnElement")
            val waypoints = edge.getElementsByTagNameNS(DD_NS, "waypoint")
            val points = (0 until waypoints.length)
                .map { waypoints.item(it) as Element }
                .mapNotNull { wp ->
                    val x = wp.getAttribute("x").toDoubleOrNull() ?: return@mapNotNull null
                    val y = wp.getAttribute("y").toDoubleOrNull() ?: return@mapNotNull null
                    DiPoint(x, y)
                }
            DiEdge(id, points)
        }
}

/** Pairs of rects (by index order) whose bounds overlap by more than a hairline. */
internal fun overlappingPairs(rects: List<DiRect>): List<Pair<DiRect, DiRect>> {
    val result = mutableListOf<Pair<DiRect, DiRect>>()
    for (i in rects.indices) {
        for (j in i + 1 until rects.size) {
            val a = rects[i]
            val b = rects[j]
            val overlapX = minOf(a.right, b.right) - maxOf(a.x, b.x)
            val overlapY = minOf(a.bottom, b.bottom) - maxOf(a.y, b.y)
            if (overlapX >= 1.0 && overlapY >= 1.0) {
                result += a to b
            }
        }
    }
    return result
}

/**
 * Whether segment `p1`-`p2` properly crosses segment `p3`-`p4` — an interior intersection, not a
 * shared endpoint or a collinear overlap (both of those are legitimate at edge junctions/termini).
 */
internal fun segmentsCross(p1: DiPoint, p2: DiPoint, p3: DiPoint, p4: DiPoint): Boolean {
    fun cross(o: DiPoint, a: DiPoint, b: DiPoint) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val d1 = cross(p3, p4, p1)
    val d2 = cross(p3, p4, p2)
    val d3 = cross(p1, p2, p3)
    val d4 = cross(p1, p2, p4)
    return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)
}

private fun segmentPairCrossings(a: DiEdge, b: DiEdge): Int {
    var count = 0
    for (i in 0 until a.waypoints.size - 1) {
        for (j in 0 until b.waypoints.size - 1) {
            if (segmentsCross(a.waypoints[i], a.waypoints[i + 1], b.waypoints[j], b.waypoints[j + 1])) count++
        }
    }
    return count
}

/** Count of properly-crossing segment pairs between the polylines of distinct edges. */
internal fun countCrossings(edges: List<DiEdge>): Int {
    var count = 0
    for (i in edges.indices) {
        for (j in i + 1 until edges.size) {
            if (edges[i].id == edges[j].id) continue
            count += segmentPairCrossings(edges[i], edges[j])
        }
    }
    return count
}

/** Sum, over all edges, of interior waypoints (a straight 2-waypoint edge contributes 0 bends). */
internal fun countBends(edges: List<DiEdge>): Int = edges.sumOf { maxOf(0, it.waypoints.size - 2) }

/** Minimum straight length of an edge's terminal (first or last) segment, per AD-622-10. */
internal const val MIN_TERMINAL_TAIL = 20.0

/** Tolerance for "on the rectangle's boundary" checks, absorbing floating-point rounding. */
private const val BOUNDARY_TOLERANCE = 1.0

/** Floating-point epsilon for axis-alignment: absorbs sub-pixel ELK routing round-off. */
private const val AXIS_EPSILON = 1e-6

/** Whether `a`-`b` runs purely horizontally or purely vertically. */
internal fun isAxisAligned(a: DiPoint, b: DiPoint): Boolean =
    kotlin.math.abs(a.x - b.x) < AXIS_EPSILON || kotlin.math.abs(a.y - b.y) < AXIS_EPSILON

/** Straight-line length of an axis-aligned segment `a`-`b`. */
internal fun axisSegmentLength(a: DiPoint, b: DiPoint): Double =
    if (a.x == b.x) kotlin.math.abs(b.y - a.y) else kotlin.math.abs(b.x - a.x)

/** Whether segment `a`-`b` passes through the interior of, or terminates inside, rect [r]. */
internal fun segmentIntersectsRect(a: DiPoint, b: DiPoint, r: DiRect): Boolean {
    fun inside(p: DiPoint) = p.x > r.x && p.x < r.right && p.y > r.y && p.y < r.bottom
    if (inside(a) || inside(b)) return true
    val corners = listOf(
        DiPoint(r.x, r.y),
        DiPoint(r.right, r.y),
        DiPoint(r.right, r.bottom),
        DiPoint(r.x, r.bottom),
    )
    return corners.indices.any { i ->
        segmentsCross(a, b, corners[i], corners[(i + 1) % corners.size])
    }
}

/** Whether [p] lies on (or within [BOUNDARY_TOLERANCE] of) one of [r]'s four sides. */
internal fun isOnRectBoundary(p: DiPoint, r: DiRect): Boolean {
    val withinX = p.x in (r.x - BOUNDARY_TOLERANCE)..(r.right + BOUNDARY_TOLERANCE)
    val withinY = p.y in (r.y - BOUNDARY_TOLERANCE)..(r.bottom + BOUNDARY_TOLERANCE)
    if (!withinX || !withinY) return false
    val onVerticalEdge = kotlin.math.abs(p.x - r.x) <= BOUNDARY_TOLERANCE ||
        kotlin.math.abs(p.x - r.right) <= BOUNDARY_TOLERANCE
    val onHorizontalEdge = kotlin.math.abs(p.y - r.y) <= BOUNDARY_TOLERANCE ||
        kotlin.math.abs(p.y - r.bottom) <= BOUNDARY_TOLERANCE
    return onVerticalEdge || onHorizontalEdge
}
