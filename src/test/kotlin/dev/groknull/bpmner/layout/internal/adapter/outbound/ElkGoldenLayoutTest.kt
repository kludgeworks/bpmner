/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer 4b: rendered-invariant oracle over the full 12-fixture corpus (AD-557-11/12 re-homing).
 *
 * Previous form: byte-compared 8 of 12 goldens — certifies *old code*, not *correct geometry*.
 * The byte-compare gate would fail a correct constraint-based reimplementation.
 *
 * Current form: per-fixture geometry invariants over all 12 corpus fixtures. Each run:
 * 1. Applies geometry invariants (positive bounds, waypoints ≥ 2, labels below nodes).
 * 2. Asserts layout is deterministic across two runs (byte-equality within one engine version).
 *
 * Boundary-specific and subprocess-specific invariants are in the boundary/containment
 * tests in [ElkBpmnLayouterTest], which share the same corpus fixtures and cover the same
 * geometry properties. This file covers the cross-cutting invariants applicable to all 12.
 *
 * Approved golden files remain committed under `bpmn/elk-corpus/golden/` as references
 * for HITL passes.
 */
class ElkGoldenLayoutTest {

    private val layouter = ElkBpmnLayouter()

    companion object {
        private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
        private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"
        private const val DD_NS = "http://www.omg.org/spec/DD/20100524/DI"
    }

    @ParameterizedTest(name = "geometry invariants: {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "long-labels",
            "annotation-and-group",
            "subprocess-flat",
            "subprocess-branch",
            "subprocess-loop",
            "subprocess-nested",
            "boundary-timer-task",
            "boundary-error-task",
            "boundary-multi",
            "boundary-on-subprocess",
        ],
    )
    @Suppress("CyclomaticComplexMethod")
    fun `all 12 corpus fixtures satisfy geometry invariants`(fixture: String) {
        val input = load("bpmn/elk-corpus/$fixture.bpmn")
        val result = layouter.layout(input)
        val doc = LayoutDiInspector.parse(result)

        assertOneDiagram(doc, fixture)
        assertPositiveBounds(doc, fixture)
        assertMinEdgeWaypoints(doc, fixture)
        assertLabelsBelow(doc, fixture)
    }

    private fun assertOneDiagram(doc: org.w3c.dom.Document, fixture: String) {
        val diagrams = doc.getElementsByTagNameNS(DI_NS, "BPMNDiagram")
        assertEquals(1, diagrams.length, "[$fixture] must have exactly one BPMNDiagram")
    }

    private fun assertPositiveBounds(doc: org.w3c.dom.Document, fixture: String) {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        assertTrue(shapes.length > 0, "[$fixture] must have at least one BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as Element
            val id = shape.getAttribute("bpmnElement")
            val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: continue
            val w = bounds.getAttribute("width").toDoubleOrNull() ?: 0.0
            val h = bounds.getAttribute("height").toDoubleOrNull() ?: 0.0
            assertTrue(w > 0.0, "[$fixture] shape '$id' must have positive width, was $w")
            assertTrue(h > 0.0, "[$fixture] shape '$id' must have positive height, was $h")
        }
    }

    private fun assertMinEdgeWaypoints(doc: org.w3c.dom.Document, fixture: String) {
        val edges = doc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as Element
            val id = edge.getAttribute("bpmnElement")
            val wps = edge.getElementsByTagNameNS(DD_NS, "waypoint")
            assertTrue(wps.length >= 2, "[$fixture] edge '$id' must have ≥ 2 waypoints, had ${wps.length}")
        }
    }

    private fun assertLabelsBelow(doc: org.w3c.dom.Document, fixture: String) {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .forEach { shape ->
                assertShapeLabelBelow(shape, fixture)
            }
    }

    private fun assertShapeLabelBelow(shape: Element, fixture: String) {
        val id = shape.getAttribute("bpmnElement")
        val sb = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return
        val lbl = shape.getElementsByTagNameNS(DI_NS, "BPMNLabel").item(0) as? Element ?: return
        val lb = lbl.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return
        val shapeY = sb.getAttribute("y").toDoubleOrNull()
        val shapeH = sb.getAttribute("height").toDoubleOrNull()
        val labelY = lb.getAttribute("y").toDoubleOrNull()
        if (shapeY == null || shapeH == null || labelY == null) return
        assertTrue(
            labelY >= shapeY + shapeH - 1.0,
            "[$fixture] shape '$id' label (y=$labelY) must be below shape bottom (y=$shapeY h=$shapeH)",
        )
    }

    @ParameterizedTest(name = "determinism: {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "long-labels",
            "annotation-and-group",
            "subprocess-flat",
            "subprocess-branch",
            "subprocess-loop",
            "subprocess-nested",
            "boundary-timer-task",
            "boundary-error-task",
            "boundary-multi",
            "boundary-on-subprocess",
        ],
    )
    fun `layout is deterministic across two runs`(fixture: String) {
        val input = load("bpmn/elk-corpus/$fixture.bpmn")
        val first = layouter.layout(input)
        val second = layouter.layout(input)
        assertEquals(first, second, "Layout was non-deterministic for fixture '$fixture'")
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
