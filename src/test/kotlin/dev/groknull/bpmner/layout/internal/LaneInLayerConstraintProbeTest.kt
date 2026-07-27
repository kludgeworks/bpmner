/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertTrue

/**
 * ELK's hierarchical compound nesting alone (each lane a real `INCLUDE_CHILDREN` compound
 * under its participant, per `BpmnToElkMapper.mapLane`) does not stack sibling lanes. This
 * probe inspects ELK's *raw* output (`BpmnToElkMapper.map` + `RecursiveGraphLayoutEngine`,
 * bypassing the placement pipeline) on `collab-lanes.bpmn`, whose three lanes declare Sales,
 * Warehouse, Delivery top-to-bottom: each lane compound is correctly *sized* (its bounds
 * tightly contain its own members), but all three land at the same absolute Y and fully
 * overlap — ELK has no reason to separate sibling compounds vertically when no edge runs
 * directly between them (only between their nested descendant members).
 *
 * `CollaborationFramePlacement.projectLaneBands` is therefore responsible for stacking
 * lanes in BPMN's declared order and translating their members; that responsibility cannot
 * currently be delegated to plain compound nesting.
 */
class LaneInLayerConstraintProbeTest {

    @Test
    fun `raw ELK lane compounds overlap instead of stacking in BPMN's declared order`() {
        val xml = load("layout-fixtures/collab-lanes.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        ElkBpmnLayouter().registerElkLayoutAlgorithm()
        val skeleton = BpmnToElkMapper.map(model)
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())

        // ELK compound coordinates are relative to their parent, so absolutePosition (the same
        // walk-up-the-parent-chain NodeShapeCopy uses for ordinary flow nodes) is required to
        // compare lanes' true canvas positions rather than each lane's own local frame.
        val (_, salesY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_sales"))
        val (_, warehouseY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_warehouse"))
        val (_, deliveryY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_delivery"))

        assertTrue(
            salesY == warehouseY && warehouseY == deliveryY,
            "expected all three lane compounds to land at the same absolute Y (recorded finding: " +
                "32.0), got sales=$salesY warehouse=$warehouseY delivery=$deliveryY — if they now " +
                "differ, plain compound nesting may already separate lanes and L1 should be re-probed",
        )
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")
}
