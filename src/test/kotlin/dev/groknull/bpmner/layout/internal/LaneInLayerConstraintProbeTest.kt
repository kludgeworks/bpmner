/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertFalse

/**
 * #622's L1 spike proved plain ELK compound nesting does not stack sibling lane compounds; #730's
 * decision spike (`LaneConstraintSpikeTest`) went further and proved that no stock declarative
 * lane encoding bands them either (AD-730-05). AD-730-06 therefore removes lanes from the ELK
 * compound graph entirely: a lane is a routing constraint on its members
 * (`BpmnToElkMapper.applyLaneBand`/`applyLaneConstraint`), never its own ELK node.
 *
 * This probe now guards that structural decision directly, on the same fixture the original L1
 * spike used: `collab-lanes.bpmn`'s three declared lanes must never appear as ELK nodes.
 */
class LaneInLayerConstraintProbeTest {

    @Test
    fun `declared lanes never become their own ELK node`() {
        val xml = load("layout-fixtures/collab-lanes.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val skeleton = BpmnToElkMapper.map(model)

        for (laneId in listOf("Lane_sales", "Lane_warehouse", "Lane_delivery")) {
            assertFalse(laneId in skeleton.nodeMap, "'$laneId' must not be mapped as an ELK node (AD-730-06)")
        }
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")
}
