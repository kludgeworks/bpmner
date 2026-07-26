/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-622-24 probe (`PLAN-622-4.md` §3.2): `miwg-b2-dense.bpmn`, a MIWG `B.2.0`-derived fixture
 * sized to exercise routing density — 78 sequence flows, all four gateway types, 8 interrupting
 * plus 1 non-interrupting boundary event, 4 embedded subprocesses, 1 event subprocess, both
 * loop-characteristic variants, one compensation handler, one signal throw, one escalation
 * throw, and one data object/store pair — now grown to the full vocabulary this epic supports
 * (close-out's first step, per `ARCHITECTURE.md` §5's B.2.0 item).
 *
 * **Enrolment attempted this session and reverted on evidence, not left silent.** Adding the
 * fixture to [LAYOUT_CORPUS_FIXTURES] and running the full invariant suite surfaced exactly one
 * failure: `ElkGoldenLayoutTest`'s `assertLabelsClearOtherDiGeometry` — `Task_par_a`'s own name
 * label lands inside `Task_par_b`'s shape. Measured cause: `Task_par_a` carries a boundary event
 * (`Boundary_par_a_error`), and ELK stacks that boundary's label directly above `Task_par_a`'s
 * own label with zero gap (`y` 162-190 then 190-218) — but the *next sibling* in the same
 * layer, `Task_par_b`, is placed at `y` 185, using only `Task_par_a`'s shape-plus-boundary
 * extent, not either label's height. This is the same class of gap AD-622-37 already found and
 * accepted ("`InnermostNodeMarginCalculator` — a structural ELK gap, not our defect"), newly
 * visible here on label-vs-*shape* rather than label-vs-edge, which is why it isn't caught by
 * the corpus's existing `LABEL_EDGE_OVERLAP_EXCEPTIONS` mechanism (label-vs-edge only).
 * Extending that exception apparatus to label-vs-shape, or root-causing a general fix, is a
 * corpus-wide test-design call this probe's budget doesn't cover — it is the next, named,
 * concrete step for whoever finishes B.2.0 enrolment.
 *
 * Un-enrolled: no golden, no [LAYOUT_CORPUS_FIXTURES] entry, no baseline row. This test only
 * asserts the fixture lays out at all and produces structurally valid DI; it records metrics
 * rather than asserting on them.
 */
class DenseRoutingProbeTest {

    @Test
    fun `dense routing fixture lays out to structurally valid DI`() {
        val xml = load("layout-fixtures/miwg-b2-dense.bpmn")
        val result = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(result)

        val planes = doc.getElementsByTagNameNS(DI_NS, "BPMNPlane")
        assertTrue(planes.length == 1, "must produce exactly one BPMNPlane, had ${planes.length}")

        val shapeElements = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        val shapeRects = extractShapeRects(doc)
        assertTrue(shapeElements.length > 0, "must produce at least one BPMNShape")
        assertEquals(
            shapeElements.length,
            shapeRects.size,
            "every BPMNShape must have positive-area dc:Bounds (extractShapeRects drops invalid ones)",
        )

        val edges = extractEdges(doc)
        assertTrue(edges.isNotEmpty(), "must produce at least one BPMNEdge")
        edges.forEach { edge ->
            assertTrue(
                edge.waypoints.size >= 2,
                "edge '${edge.id}' must have >= 2 valid waypoints, had ${edge.waypoints.size}",
            )
        }

        val metrics = layoutMetrics(doc)
        println(
            "[miwg-b2-dense] recorded metrics: crossings=${metrics.crossings}, " +
                "bends=${metrics.bends}, overlaps=${metrics.overlaps} " +
                "(un-enrolled probe — no baseline; triage any defect as a 622-2/622-3 finding, " +
                "per PLAN-622-4.md §3.2)",
        )
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")

    companion object {
        private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
    }
}
