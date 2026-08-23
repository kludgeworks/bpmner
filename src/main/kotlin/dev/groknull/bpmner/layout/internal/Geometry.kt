/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

/**
 * Whether segment `p1`-`p2` properly crosses segment `p3`-`p4` — an interior intersection, not a
 * shared endpoint or a collinear overlap (both of those are legitimate at edge junctions/termini).
 */
internal fun segmentsCross(
    p1: BpmnPlacementPass.Point,
    p2: BpmnPlacementPass.Point,
    p3: BpmnPlacementPass.Point,
    p4: BpmnPlacementPass.Point,
): Boolean {
    fun cross(o: BpmnPlacementPass.Point, a: BpmnPlacementPass.Point, b: BpmnPlacementPass.Point) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val d1 = cross(p3, p4, p1)
    val d2 = cross(p3, p4, p2)
    val d3 = cross(p1, p2, p3)
    val d4 = cross(p1, p2, p4)
    return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) && (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)
}

/** Whether segment `a`-`b` passes through the interior of, or terminates inside, rect [r]. */
internal fun segmentIntersectsRect(
    a: BpmnPlacementPass.Point,
    b: BpmnPlacementPass.Point,
    r: BpmnPlacementPass.Rect,
): Boolean {
    fun inside(p: BpmnPlacementPass.Point) = p.x > r.x && p.x < r.x + r.w && p.y > r.y && p.y < r.y + r.h
    if (inside(a) || inside(b)) return true
    val corners = listOf(
        BpmnPlacementPass.Point(r.x, r.y),
        BpmnPlacementPass.Point(r.x + r.w, r.y),
        BpmnPlacementPass.Point(r.x + r.w, r.y + r.h),
        BpmnPlacementPass.Point(r.x, r.y + r.h),
    )
    return corners.indices.any { i ->
        segmentsCross(a, b, corners[i], corners[(i + 1) % corners.size])
    }
}
