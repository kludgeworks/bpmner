/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Cross-participant message flows are deliberately excluded from the ELK graph
 * (`BpmnToElkMapper.mapMessageFlows` guards against mapping them as real ELK edges): mapping
 * them measurably perturbs a participant's own internal layout. Laying out
 * `miwg-c2-four-pools.bpmn` with the guard removed moves `Task_receive_order` ~58px
 * vertically relative to its own pool (`Participant_retailer`) — a real internal reordering
 * from the cross-hierarchy edges' port dummies feeding the shared crossing minimisation, not
 * a uniform resize.
 *
 * This test pins `Task_receive_order`'s position relative to `Participant_retailer` as a
 * regression guard for the guard-in-place baseline.
 */
class CrossParticipantMessageFlowProbeTest {
    @Test
    fun `retailer participant's internal layout is stable with the cross-participant guard in place`() {
        val xml = javaClass.classLoader.getResourceAsStream("layout-fixtures/miwg-c2-four-pools.bpmn")
            ?.bufferedReader()?.readText() ?: error("fixture not found")
        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)

        val participant = LayoutDiInspector.shapeBounds(doc, "Participant_retailer")
        val task = LayoutDiInspector.shapeBounds(doc, "Task_receive_order")
        val relX = task["x"]!! - participant["x"]!!
        val relY = task["y"]!! - participant["y"]!!

        assertTrue(abs(relX - EXPECTED_REL_X) < TOLERANCE, "Task_receive_order x offset: expected ~$EXPECTED_REL_X, got $relX")
        assertTrue(abs(relY - EXPECTED_REL_Y) < TOLERANCE, "Task_receive_order y offset: expected ~$EXPECTED_REL_Y, got $relY")
    }

    private companion object {
        const val TOLERANCE = 0.1
        const val EXPECTED_REL_X = 224.03515625
        const val EXPECTED_REL_Y = 137.0
    }
}
