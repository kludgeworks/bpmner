/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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

    @Test
    fun `nonContainerObstacles excludes header owners, boundary events and container-sized shapes`() {
        val task = DiRect("Task_1", 0.0, 0.0, 100.0, 80.0)
        val boundary = DiRect("Boundary_1", 50.0, 76.0, 36.0, 36.0)
        val lane = DiRect("Lane_1", 0.0, 0.0, 500.0, 160.0)
        val subprocess = DiRect("Sub_1", 0.0, 0.0, 300.0, 200.0)
        val result = nonContainerObstacles(
            listOf(task, boundary, lane, subprocess),
            headerOwnerIds = setOf("Lane_1"),
            boundaryEventIds = setOf("Boundary_1"),
        )
        assertEquals(listOf(task), result)
    }

    @Test
    fun `leftmostFirstLaneViolations is empty when the start event is leftmost and in the first lane`() {
        val shapes = mapOf(
            "Start_1" to DiRect("Start_1", 0.0, 0.0, 36.0, 36.0),
            "Task_1" to DiRect("Task_1", 100.0, 0.0, 100.0, 80.0),
        )
        val violations = leftmostFirstLaneViolations(
            flowNodeIds = listOf("Start_1", "Task_1"),
            startId = "Start_1",
            shapes = shapes,
            laneIndexOf = mapOf("Start_1" to 0, "Task_1" to 0),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `leftmostFirstLaneViolations detects a node left of the start event and a start not in lane 0`() {
        val shapes = mapOf(
            "Start_1" to DiRect("Start_1", 100.0, 0.0, 36.0, 36.0),
            "Task_1" to DiRect("Task_1", 0.0, 0.0, 100.0, 80.0),
        )
        val violations = leftmostFirstLaneViolations(
            flowNodeIds = listOf("Start_1", "Task_1"),
            startId = "Start_1",
            shapes = shapes,
            laneIndexOf = mapOf("Start_1" to 1, "Task_1" to 0),
        )
        assertEquals(2, violations.size)
    }

    @Test
    fun `monotonicXViolations is empty for a forward flow and exempts a declared back-edge`() {
        val forward = DiEdge("Flow_1", listOf(DiPoint(0.0, 0.0), DiPoint(10.0, 0.0)))
        val backward = DiEdge("Flow_2", listOf(DiPoint(10.0, 0.0), DiPoint(0.0, 0.0)))
        val violations = monotonicXViolations(
            flowIds = listOf("Flow_1", "Flow_2"),
            edgesById = mapOf("Flow_1" to forward, "Flow_2" to backward),
            backEdgeIds = setOf("Flow_2"),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `monotonicXViolations detects a non-back-edge flow that decreases X`() {
        val backward = DiEdge("Flow_1", listOf(DiPoint(10.0, 0.0), DiPoint(0.0, 0.0)))
        val violations = monotonicXViolations(
            flowIds = listOf("Flow_1"),
            edgesById = mapOf("Flow_1" to backward),
            backEdgeIds = emptySet(),
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `rightwardLaneTransitionViolations detects a segment that regresses`() {
        val edge = DiEdge("Flow_1", listOf(DiPoint(0.0, 0.0), DiPoint(20.0, 0.0), DiPoint(10.0, 5.0)))
        val violations = rightwardLaneTransitionViolations(listOf("Flow_1"), mapOf("Flow_1" to edge))
        assertEquals(1, violations.size)
    }

    @Test
    fun `unrelatedNodeIntersections excludes the flow's own endpoints and a container obstacle already filtered out`() {
        val edge = DiEdge("Flow_1", listOf(DiPoint(0.0, 0.0), DiPoint(100.0, 0.0)))
        val source = DiRect("Task_A", -10.0, -10.0, 20.0, 20.0)
        val target = DiRect("Task_B", 90.0, -10.0, 20.0, 20.0)
        val violations = unrelatedNodeIntersections(
            flows = listOf(Triple("Flow_1", "Task_A", "Task_B")),
            edgesById = mapOf("Flow_1" to edge),
            obstacles = listOf(source, target),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `unrelatedNodeIntersections detects a flow that crosses an unrelated obstacle`() {
        val edge = DiEdge("Flow_1", listOf(DiPoint(0.0, 0.0), DiPoint(100.0, 0.0)))
        val obstacle = DiRect("Task_C", 40.0, -10.0, 20.0, 20.0)
        val violations = unrelatedNodeIntersections(
            flows = listOf(Triple("Flow_1", "Task_A", "Task_B")),
            edgesById = mapOf("Flow_1" to edge),
            obstacles = listOf(obstacle),
        )
        assertEquals(1, violations.size)
    }

    @Test
    fun `fixtureEnrollmentViolations is empty when the resource set matches the declared list exactly`() {
        val violations = fixtureEnrollmentViolations(setOf("a", "b"), declared = listOf("a", "b"))
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `fixtureEnrollmentViolations detects an unenrolled golden and a declared fixture with no golden`() {
        val violations = fixtureEnrollmentViolations(setOf("a", "c"), declared = listOf("a", "b"))
        assertEquals(2, violations.size)
    }
}
