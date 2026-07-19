/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.ExceptionEdgeRoutes
import dev.groknull.bpmner.layout.internal.placement.ExceptionEdgeRoutes.EXCEPTION_DETOUR_GAP
import dev.groknull.bpmner.layout.internal.placement.ExceptionEdgeRoutes.routeExceptionEdge
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [ExceptionEdgeRoutes] (pipeline entry 7).
 *
 * Tests the three-point normal route AND the five-point detour case (handler above main flow —
 * currently untested elsewhere, per plan §C1 priority 4).
 */
class ExceptionEdgeRoutesTest {

    // ── Normal case: handler below or beside ─────────────────────────────────

    @Test
    fun `three-point drop-then-horizontal route for handler below boundary`() {
        // Boundary: x=130, y=120, w=36, h=36 → centre=(148,138), bottom=156
        val bRect = Rect(130.0, 120.0, 36.0, 36.0)
        // Handler: x=200, y=200, w=100, h=80 → centre=(250,240), left=200
        val tRect = Rect(200.0, 200.0, 100.0, 80.0)
        // No host provided (or host doesn't block)
        val wps = routeExceptionEdge(bRect, tRect, null)

        assertEquals(3, wps.size, "Normal case must produce 3 waypoints")
        val bCx = bRect.x + bRect.w / 2.0 // 148
        val bBottom = bRect.y + bRect.h // 156
        val handlerCy = tRect.y + tRect.h / 2.0 // 240
        val enterX = tRect.x // handler is to the right, enter left edge

        assertNear(bCx, wps[0].x, "wp0.x must be boundary centre-X")
        assertNear(bBottom, wps[0].y, "wp0.y must be boundary bottom")
        assertNear(bCx, wps[1].x, "wp1.x must stay at boundary centre-X (vertical drop)")
        assertNear(handlerCy, wps[1].y, "wp1.y must be handler centre-Y")
        assertNear(enterX, wps[2].x, "wp2.x must be handler left edge")
        assertNear(handlerCy, wps[2].y, "wp2.y must be handler centre-Y")

        assertOrthogonal(wps)
    }

    @Test
    fun `route enters handler right edge when handler is to the left of the boundary`() {
        // Boundary centre-X = 400; handler right edge at 300 < 400 → enter right edge
        val bRect = Rect(382.0, 120.0, 36.0, 36.0) // centre-X=400
        val tRect = Rect(200.0, 200.0, 100.0, 80.0) // right=300 < 400
        val wps = routeExceptionEdge(bRect, tRect, null)

        val handlerRight = tRect.x + tRect.w // 300
        assertNear(handlerRight, wps[2].x, "wp2.x must enter handler right edge when handler is to the left")
    }

    // ── Detour case: straight vertical would pass through the host ────────────

    @Test
    fun `five-point detour route when vertical at boundary centre-X crosses the host`() {
        // Host: x=100, y=50, w=200, h=150 → right=300, bottom=200
        // Boundary on host bottom: centre-X at 200 (within host x-range 100..300)
        // boundary: x=182, y=182, w=36, h=36 → bCx=200, bBottom=218
        val bRect = Rect(182.0, 182.0, 36.0, 36.0)
        // Handler above main flow at y=-50: the straight vertical from bCx=200 to handlerCy=~-32
        // would pass through the host (y range 50..200 contains part of -32..218 overlap at 50..218)
        val tRect = Rect(250.0, -80.0, 100.0, 80.0) // centre-Y=-40, above host
        val hostRect = Rect(100.0, 50.0, 200.0, 150.0)

        val wps = routeExceptionEdge(bRect, tRect, hostRect)
        assertEquals(5, wps.size, "Detour case must produce 5 waypoints")

        val bCx = bRect.x + bRect.w / 2.0 // 200
        val bBottom = bRect.y + bRect.h // 218

        // wp0: boundary bottom centre
        assertNear(bCx, wps[0].x, "wp0.x must be boundary centre-X")
        assertNear(bBottom, wps[0].y, "wp0.y must be boundary bottom")

        // wp1: same X, drop just below boundary (clearY = bBottom + gap)
        assertNear(bCx, wps[1].x, "wp1.x must stay at boundary centre-X")
        assertNear(bBottom + EXCEPTION_DETOUR_GAP, wps[1].y, "wp1.y must be clearY")

        // wp2: detour right of host
        val clearX = hostRect.x + hostRect.w + EXCEPTION_DETOUR_GAP // 320
        assertNear(clearX, wps[2].x, "wp2.x must be clearX (right of host)")

        // All segments orthogonal
        assertOrthogonal(wps)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun assertNear(expected: Double, actual: Double, msg: String, eps: Double = 0.5) {
        assertTrue(
            kotlin.math.abs(actual - expected) <= eps,
            "$msg: expected $expected±$eps, got $actual",
        )
    }

    private fun assertOrthogonal(wps: List<BpmnPlacementPass.Point>) {
        for (i in 1 until wps.size) {
            val a = wps[i - 1]
            val b = wps[i]
            assertTrue(
                kotlin.math.abs(a.x - b.x) < 0.5 || kotlin.math.abs(a.y - b.y) < 0.5,
                "Segment $a→$b must be axis-aligned",
            )
        }
    }
}
