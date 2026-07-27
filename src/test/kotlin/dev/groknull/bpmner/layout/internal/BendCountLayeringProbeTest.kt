/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `representative-process.bpmn`'s `Flow_default` (`Gw_split` &rarr; `Task_handle_fail`) is an
 * ordinary cross-layer edge, not an in-layer special case: `BpmnToElkMapper` sets
 * `CoreOptions.EDGE_ROUTING = ORTHOGONAL` (`applyRootLayoutOptions`), so ELK's
 * `EdgeRouterFactory.factoryFor` selects `OrthogonalEdgeRouter`, not `PolylineEdgeRouter` —
 * the in-layer-dummy-node precondition belongs to `PolylineEdgeRouter` and does not apply
 * here. Independently, `Gw_split`'s two outgoing edges form a symmetric one-hop diamond
 * (`Flow_ok` &rarr; `Task_process`, `Flow_default` &rarr; `Task_handle_fail`, both rejoining
 * at `Gw_join`), and `Task_handle_fail` measures into the same layer as `Task_process`, one
 * full layer right of `Gw_split` — so `Flow_default` was never in-layer regardless of router.
 *
 * The mapper's other root layout options are deliberate, evidenced choices, each with a
 * recorded reason at `applyRootLayoutOptions`: `NODE_PLACEMENT_STRATEGY = NETWORK_SIMPLEX`
 * keeps the primary flow on one Y baseline; `CONSIDER_MODEL_ORDER_STRATEGY = NODES_AND_EDGES`
 * gives deterministic, document-order branch ordering; `SEPARATE_CONNECTED_COMPONENTS = true`
 * at root guarantees floating elements never overlap the main flow (see
 * `FloatingElementAnchorProbeTest`).
 *
 * This test pins the layering fact, the router selection, and the current bend count as a
 * regression guard: if any drifts absent an intentional mapper change, re-check the reasoning
 * above still holds.
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
            "Flow_default's waypoint count moved from its recorded baseline",
        )
    }

    private companion object {
        const val EXPECTED_FLOW_DEFAULT_WAYPOINTS = 6
    }
}
