/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q3 probe (622-Y, AD-622-43): is `representative-process.bpmn`'s `Flow_default`
 * (`Gw_split` &rarr; `Task_handle_fail`) the `PolylineEdgeRouter` in-layer special case
 * AD-622-43 flagged as the likeliest lever — "at least one of the nodes connected by an
 * in-layer edge is a dummy node" — or an ordinary cross-layer edge?
 *
 * **Verdict: ordinary cross-layer edge; the in-layer hypothesis is falsified.** `Gw_split`'s
 * two outgoing edges are a symmetric one-hop diamond (`Flow_ok` &rarr; `Task_process`,
 * `Flow_default` &rarr; `Task_handle_fail`, both rejoining at `Gw_join`). Measured x-position
 * confirms `Task_handle_fail` sits in the same layer as `Task_process` — one full layer right
 * of `Gw_split`, not `Gw_split`'s own layer. `Flow_default` is therefore an adjacent-layer
 * edge like `Flow_ok`, and `PolylineEdgeRouter`'s in-layer precondition never applies to it.
 * The pointer AD-622-43 flagged as "the cheapest possible outcome" to check first does not
 * hold; layering is not the lever.
 *
 * The observed six-waypoint route is consistent with an ordinary artifact of two edges
 * fanning from one gateway across a wide vertical gap (`Task_process` at y&approx;52,
 * `Task_handle_fail` at y&approx;227) through the inter-layer routing space — not a
 * root-caused defect this probe found a fix for. Per #591's disposition rule as carried by
 * AD-622-43 ("a targeted root-cause fix, or a floor re-recorded against fresh evidence"),
 * this is the floor: fresh evidence, one lever eliminated, no fix attempted blind. The
 * remaining levers are the foundational, corpus-wide options #591 already declined to sweep
 * (`NODE_PLACEMENT_STRATEGY`, `CONSIDER_MODEL_ORDER_STRATEGY`,
 * `SEPARATE_CONNECTED_COMPONENTS`) — architect-scoped, not a probe's to try blind.
 *
 * This test pins both the layering fact and the current bend count as a regression guard: if
 * either drifts absent an intentional mapper change, that is a signal this probe's premise
 * has changed and Q3 should be re-run.
 */
class BendCountLayeringProbeTest {

    @Test
    fun `Flow_default is an ordinary cross-layer edge, not an in-layer special case`() {
        val xml = LayoutDiInspector.loadCorpus(javaClass.classLoader, "representative-process.bpmn")
        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)

        val split = LayoutDiInspector.shapeBounds(doc, "Gw_split")
        val process = LayoutDiInspector.shapeBounds(doc, "Task_process")
        val handleFail = LayoutDiInspector.shapeBounds(doc, "Task_handle_fail")

        assertEquals(
            process["x"],
            handleFail["x"],
            "Task_handle_fail must share Task_process's layer (same x) for the diamond to be symmetric",
        )
        assertTrue(
            handleFail["x"]!! > split["x"]!!,
            "Task_handle_fail must sit strictly right of Gw_split's own layer, not in it",
        )

        val bends = LayoutDiInspector.edgeWaypoints(doc, "Flow_default").size
        assertEquals(
            EXPECTED_FLOW_DEFAULT_WAYPOINTS,
            bends,
            "Flow_default's waypoint count moved — Q3's floor evidence is stale, re-run the probe",
        )
    }

    private companion object {
        // Six waypoints, unchanged by Q2's spacing fix (confirmed) — see class doc.
        const val EXPECTED_FLOW_DEFAULT_WAYPOINTS = 6
    }
}
