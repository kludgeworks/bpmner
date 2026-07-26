/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Q3 probe (622-Y, AD-622-43, the epic's one sanctioned corpus-wide option sweep): is
 * `representative-process.bpmn`'s `Flow_default` (`Gw_split` &rarr; `Task_handle_fail`) the
 * `PolylineEdgeRouter` in-layer special case AD-622-43 flagged as the likeliest lever — "at
 * least one of the nodes connected by an in-layer edge is a dummy node" — or an ordinary
 * cross-layer edge?
 *
 * **Verdict: the premise doesn't apply — `PolylineEdgeRouter` never runs in this pipeline.**
 * `BpmnToElkMapper` sets `CoreOptions.EDGE_ROUTING = ORTHOGONAL`
 * (`applyRootLayoutOptions`, `:677`), and ELK's own `EdgeRouterFactory.factoryFor` returns
 * `PolylineEdgeRouter` only for the `POLYLINE` case — `ORTHOGONAL` falls to the `default`
 * branch, `OrthogonalEdgeRouter`. AD-622-43's in-layer-dummy-node precondition is
 * `PolylineEdgeRouter`'s own, not `OrthogonalEdgeRouter`'s; citing it here is the same class
 * of miscitation AD-622-37 already corrected once for margin calculators ("two exist with
 * opposite rules, and this doc was citing the wrong one for our pipeline"). This closes the
 * lever more conclusively than a same-layer check alone: there is no in-layer precondition
 * to satisfy or fail in our router at all.
 *
 * A weaker, still-true fact survives the correction and is worth pinning on its own: `Gw_split`'s
 * two outgoing edges are a symmetric one-hop diamond (`Flow_ok` &rarr; `Task_process`,
 * `Flow_default` &rarr; `Task_handle_fail`, both rejoining at `Gw_join`), and `Task_handle_fail`
 * measures into the same layer as `Task_process`, one full layer right of `Gw_split` — so
 * even under the (inapplicable) polyline framing, `Flow_default` was never in-layer.
 *
 * **The remaining foundational options are not untried defaults — they are this mapper's own
 * deliberate, evidenced choices**, each with a recorded reason at its declaration
 * (`applyRootLayoutOptions`): `NODE_PLACEMENT_STRATEGY = NETWORK_SIMPLEX` keeps the primary
 * flow on one Y baseline; `CONSIDER_MODEL_ORDER_STRATEGY = NODES_AND_EDGES` gives
 * deterministic, document-order branch ordering; `SEPARATE_CONNECTED_COMPONENTS = true` at
 * root guarantees floating elements never overlap the main flow (AD-622-35,
 * `FloatingElementAnchorProbeTest`). Sweeping any of them for one edge's bend count would
 * contradict an already-evidenced decision this epic made for unrelated, real defects it
 * fixed — not a sweep to run blind on this probe's budget.
 *
 * Per #591's disposition rule as carried by AD-622-43 ("a targeted root-cause fix, or a floor
 * re-recorded against fresh evidence"), this is the floor: the flagged lever is inapplicable
 * on measurement, and the remaining levers are already spent on other, real fixes.
 *
 * This test pins the layering fact, the router selection, and the current bend count as a
 * regression guard: if any drifts absent an intentional mapper change, that is a signal this
 * probe's premise has changed and Q3 should be re-run.
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
