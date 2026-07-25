/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * AD-622-24 probe (`PLAN-622-4.md` §3.2): `miwg-b2-dense.bpmn`, a MIWG `B.2.0`-derived fixture
 * stripped to the currently-supported profile (no signal/escalation/compensation definitions,
 * non-interrupting boundaries, event subprocesses, or data objects), sized to exercise routing
 * density — 73 sequence flows, all four gateway types, 8 interrupting boundary events, 4
 * embedded subprocesses, and both loop-characteristic variants.
 *
 * Un-enrolled: no golden, no [LAYOUT_CORPUS_FIXTURES] entry, no baseline row (AD-622-24's
 * generalised rule — a fixture that probes a mechanism enters un-enrolled; enrolment is 622-6's
 * job once its geometry is stable). This test only asserts the fixture lays out at all and
 * produces structurally valid DI; it records metrics rather than asserting on them.
 */
class DenseRoutingProbeTest {

    @Test
    fun `dense routing fixture lays out to structurally valid DI`() {
        val xml = load("layout-fixtures/miwg-b2-dense.bpmn")
        val result = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(result)

        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        val edges = doc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
        val planes = doc.getElementsByTagNameNS(DI_NS, "BPMNPlane")
        assertTrue(shapes.length > 0, "must produce at least one BPMNShape")
        assertTrue(edges.length > 0, "must produce at least one BPMNEdge")
        assertTrue(planes.length == 1, "must produce exactly one BPMNPlane, had ${planes.length}")

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
