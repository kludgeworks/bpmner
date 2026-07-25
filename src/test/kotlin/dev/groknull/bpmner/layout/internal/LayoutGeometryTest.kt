/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutGeometryTest {

    @Test
    fun `properly crossing segments are detected`() {
        // An X: (0,0)-(10,10) crosses (0,10)-(10,0) at their midpoint.
        assertTrue(segmentsCross(DiPoint(0.0, 0.0), DiPoint(10.0, 10.0), DiPoint(0.0, 10.0), DiPoint(10.0, 0.0)))
    }

    @Test
    fun `disjoint segments do not cross`() {
        assertFalse(segmentsCross(DiPoint(0.0, 0.0), DiPoint(1.0, 0.0), DiPoint(5.0, 5.0), DiPoint(6.0, 5.0)))
    }

    @Test
    fun `segments sharing only an endpoint do not count as crossing`() {
        // A termination/junction, not a crossing.
        assertFalse(segmentsCross(DiPoint(0.0, 0.0), DiPoint(10.0, 0.0), DiPoint(10.0, 0.0), DiPoint(10.0, 10.0)))
    }

    @Test
    fun `collinear overlapping segments do not count as crossing`() {
        assertFalse(segmentsCross(DiPoint(0.0, 0.0), DiPoint(10.0, 0.0), DiPoint(5.0, 0.0), DiPoint(15.0, 0.0)))
    }

    @Test
    fun `countBends is zero for a straight two-waypoint edge and counts interior waypoints otherwise`() {
        val straight = DiEdge("e1", listOf(DiPoint(0.0, 0.0), DiPoint(10.0, 0.0)))
        val bent = DiEdge("e2", listOf(DiPoint(0.0, 0.0), DiPoint(5.0, 0.0), DiPoint(5.0, 5.0), DiPoint(10.0, 5.0)))
        assertTrue(countBends(listOf(straight, bent)) == 2)
    }
}
