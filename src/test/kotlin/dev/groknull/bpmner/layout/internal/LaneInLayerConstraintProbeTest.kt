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
 * L1 spike (622-Y, AD-622-23/AD-622-04): does lane banding already fall to ELK's own
 * hierarchical solve plus graph structure (each lane already a real `INCLUDE_CHILDREN`
 * compound, nested under its participant, per `BpmnToElkMapper.mapLane`), or does it need
 * `IN_LAYER_SUCCESSOR_CONSTRAINTS` (AD-622-23 Correction 1: "the mechanism itself is
 * untested")?
 *
 * `CollaborationShapePlacement.projectLaneBands` currently force-stacks lanes in BPMN's
 * declared order and translates every member — a Move→Repair pair AD-622-02 wants gone.
 * This probe inspects ELK's *raw* output (`BpmnToElkMapper.map` + `RecursiveGraphLayoutEngine`,
 * bypassing the placement pipeline) on `collab-lanes.bpmn`, whose three lanes declare Sales,
 * Warehouse, Delivery top-to-bottom.
 *
 * **Verdict: the hierarchical compound nesting alone does not stack lanes at all.** Each
 * lane is correctly *sized* (`INCLUDE_CHILDREN` earns that for free — a lane's compound
 * bounds tightly contain its own members), but all three lane compounds land at the exact
 * same absolute Y (32.0 — see the recorded coordinates below): they fully overlap rather
 * than merely landing in the wrong order. Nothing in the current graph gives ELK a reason
 * to separate sibling compounds vertically when no edge runs directly between them (only
 * between their nested descendant members) — `INCLUDE_CHILDREN` alone gives correct
 * *sizing*, not *sequencing or separation*. This confirms AD-622-04's untested basis rather
 * than settling it — `IN_LAYER_SUCCESSOR_CONSTRAINTS` (the actual candidate mechanism) is
 * still unbuilt and unprototyped; this probe only establishes that plain compound nesting
 * is not, by itself, sufficient.
 *
 * Building and testing `IN_LAYER_SUCCESSOR_CONSTRAINTS` is real `alg.layered`-internals
 * work (risk 1) — out of this session's remaining scope. Per AD-622-23, both outcomes are
 * pre-authorised: if the mechanism is built and proven, delete `CollaborationShapePlacement`'s
 * band translation; if not, its existing translation stays as the documented floor with
 * `DECLARED_OWNERS = {CollaborationShapePlacement}`. This probe's evidence does not yet
 * support the mechanism, so the floor stands pending that follow-up prototype.
 */
class LaneInLayerConstraintProbeTest {

    @Test
    fun `raw ELK lane compounds overlap instead of stacking in BPMN's declared order`() {
        val xml = load("layout-fixtures/collab-lanes.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        ElkBpmnLayouter().registerElkLayoutAlgorithm()
        val skeleton = BpmnToElkMapper.map(model)
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())

        // Declared order: Sales, Warehouse, Delivery (top to bottom in collab-lanes.bpmn).
        // ELK compound coordinates are relative to their parent, so absolutePosition (the same
        // walk-up-the-parent-chain NodeShapeCopy uses for ordinary flow nodes) is required to
        // compare lanes' true canvas positions rather than each lane's own local frame.
        val (_, salesY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_sales"))
        val (_, warehouseY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_warehouse"))
        val (_, deliveryY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_delivery"))

        // Pinned as a regression guard: if a future ELK/mapper change ever separates these,
        // that is exactly the signal this probe should be re-run with IN_LAYER_SUCCESSOR_
        // CONSTRAINTS, since the premise (plain nesting does not separate siblings) would
        // have changed.
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
