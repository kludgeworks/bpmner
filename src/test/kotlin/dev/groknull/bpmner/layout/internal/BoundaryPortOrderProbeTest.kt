/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals

/**
 * AD-622-08 §2.4 probe: settles the "`FIXED_ORDER` per-edge ports" vs. "crossing minimization
 * owns boundary order" open question for ELK 0.12.0 — the version this codebase depends on
 * (`MODULE.bazel`'s `ELK_VERSION`).
 *
 * Finding: `org.eclipse.elk.alg.layered.intermediate`'s north/south port handling
 * (`NorthSouthPortPreprocessor`, `PortListSorter`, and every other class in the package) only
 * ever *reads* `PORT_CONSTRAINTS.isOrderFixed()` to branch behaviour; none of them *mutate* it to
 * [PortConstraints.FIXED_ORDER] (verified by disassembling every class in
 * `org.eclipse.elk.alg.layered` 0.12.0 and grepping for a `setProperty` call paired with a
 * `PortConstraints.FIXED_ORDER` load — zero matches). So `PORT_CONSTRAINTS = FIXED_SIDE`
 * (`BpmnToElkMapper.kt`'s boundary port setup, unchanged by this stage) is never auto-promoted:
 * side is fixed, but **order within that side stays crossing minimization's to decide** — exactly
 * what AD-622-08's second clause requires, with no conflict against the first. The mapper
 * therefore correctly leaves `PORT_CONSTRAINTS` untouched (§2.4: "do not resolve it in code").
 *
 * This test is the runtime regression guard for that finding: `boundary-multi.bpmn`'s two
 * boundary events on one host are declared in `id`-sorted order (`Boundary_error` before
 * `Boundary_timer`) — an arbitrary order with no relationship to the handlers' eventual layered
 * positions. If a future ELK upgrade *did* start forcing `FIXED_ORDER`, that declared order would
 * become the routing order regardless of whether it produces crossings; this test would then be
 * the first thing to catch it.
 */
class BoundaryPortOrderProbeTest {

    @Test
    fun `crossing minimization, not declaration order, decides multi-boundary port order`() {
        val layouter = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }
        val xml = load("layout-fixtures/boundary-multi.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val skeleton = BpmnToElkMapper.map(model)
        val host = skeleton.nodeMap["Task_process"] ?: error("host node missing from skeleton")
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())

        assertEquals(
            PortConstraints.FIXED_SIDE,
            host.getProperty(CoreOptions.PORT_CONSTRAINTS),
            "PORT_CONSTRAINTS must stay FIXED_SIDE — a promotion to FIXED_ORDER would mean " +
                "AD-622-08's two clauses conflict and the architect must be told, not worked around here.",
        )

        val doc = LayoutDiInspector.parse(layouter.layout(xml))
        val crossings = countCrossings(extractEdges(doc))
        assertEquals(0, crossings, "multi-boundary routing must stay crossing-free")
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")
}
