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
 * Layer 4b: golden-file regression oracle over the full 12-fixture corpus.
 *
 * For each approved expected layout under `layout-fixtures/`, asserts that the engine
 * produces byte-identical output. This is the regression gate: once a human approves
 * the output in bpmn-js and commits it, this test enforces that no subsequent engine
 * change silently shifts its coordinates. A coordinate change requires a new review
 * before the expected layout can be re-blessed.
 *
 * Also asserts cross-cutting geometry invariants (positive bounds, ≥2 waypoints,
 * labels below nodes) and determinism for all 12 fixtures.
 */
@Suppress("TooManyFunctions")
class ElkGoldenLayoutTest {

    private val layouter = ElkBpmnLayouter()

    companion object {
        private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
        private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"
        private const val DD_NS = "http://www.omg.org/spec/DD/20100524/DI"
    }

    @ParameterizedTest(name = "matches committed golden: {0}")
    @ValueSource(
        strings = [
            "representative-process",
            "explicit-cycle",
            "long-labels",
            "annotation-and-group",
            "boundary-timer-task",
            "boundary-on-subprocess",
            "boundary-error-task",
            "boundary-multi",
            "subprocess-flat",
            "subprocess-loop",
            "subprocess-branch",
            "subprocess-nested",
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
        ],
    )
    fun `engine output matches committed golden (HITL-approved)`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val golden = load("layout-fixtures/$fixture.expected.bpmn")
        val actual = layouter.layout(input)
        assertEquals(
            golden,
            actual,
            "Engine output for '$fixture' does not match the committed golden. " +
                "If the layout changed intentionally, run generate_candidate_goldens, " +
                "review in bpmn-js, and re-bless the golden before updating this test.",
        )
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
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
        ],
    )
    @Suppress("CyclomaticComplexMethod")
    fun `all 12 corpus fixtures satisfy geometry invariants`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val result = layouter.layout(input)
        val doc = LayoutDiInspector.parse(result)

        assertOneDiagram(doc, fixture)
        assertPositiveBounds(doc, fixture)
        assertMinEdgeWaypoints(doc, fixture)
        assertLabelsBelow(doc, fixture)
        assertNoTopLevelShapeOverlap(doc, fixture, boundaryEventIds(result))
        assertLabelsDoNotOverlapOwnNode(doc, fixture)
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
            val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element
            assertTrue(bounds != null, "[$fixture] shape '$id' must have bounds")
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
            "subprocess-no-start-cycle",
            "subprocess-sequential-sharing",
        ],
    )
    fun `layout is deterministic across two runs`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val first = layouter.layout(input)
        val second = layouter.layout(input)
        assertEquals(first, second, "Layout was non-deterministic for fixture '$fixture'")
    }

    /**
     * Asserts that no two non-container, non-boundary-event shapes overlap each other.
     *
     * Boundary events are excluded: they intentionally straddle their host's edge (half inside,
     * half outside) — that designed overlap is not a defect. Subprocess containers are excluded
     * because they contain their children by design. We test only "small" shapes (events, tasks,
     * gateways) that are neither boundaries nor containers.
     */
    private fun assertNoTopLevelShapeOverlap(doc: org.w3c.dom.Document, fixture: String, boundaryEventIds: Set<String>) {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        data class ShapeRect(val id: String, val x: Double, val y: Double, val w: Double, val h: Double)

        val rects = (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .mapNotNull { shape ->
                val id = shape.getAttribute("bpmnElement")
                if (id in boundaryEventIds) return@mapNotNull null
                val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return@mapNotNull null
                val x = bounds.getAttribute("x").toDoubleOrNull() ?: return@mapNotNull null
                val y = bounds.getAttribute("y").toDoubleOrNull() ?: return@mapNotNull null
                val w = bounds.getAttribute("width").toDoubleOrNull() ?: return@mapNotNull null
                val h = bounds.getAttribute("height").toDoubleOrNull() ?: return@mapNotNull null
                if (w <= 0 || h <= 0) return@mapNotNull null
                ShapeRect(id, x, y, w, h)
            }

        // Non-container: area < 40000 (200×200). Subprocesses (e.g. 300×200 = 60000) contain
        // their children by design. Boundary events (area = 36×36 ≈ 1296) are small but excluded above.
        val nonContainer = rects.filter { it.w * it.h < 40_000 }

        for (i in 0 until nonContainer.size) {
            for (j in i + 1 until nonContainer.size) {
                val a = nonContainer[i]
                val b = nonContainer[j]
                val overlapX = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
                val overlapY = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
                assertTrue(
                    overlapX < 1.0 || overlapY < 1.0,
                    "[$fixture] Non-container shapes '${a.id}' and '${b.id}' overlap by " +
                        "dx=$overlapX dy=$overlapY",
                )
            }
        }
    }

    private fun boundaryEventIds(xml: String): Set<String> {
        val model = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(
            java.io.ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
        )
        return model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Asserts that node labels do not overlap their own node's shape bounds.
     *
     * Per the placement convention, every node label is placed BELOW the shape, so the label's
     * top should be at or below the shape's bottom edge. This is already checked by [assertLabelsBelow];
     * this method additionally checks that the label's bounds do not penetrate into the shape's
     * Y-range (i.e. the label top ≥ shape bottom − 1px).
     */
    private fun assertLabelsDoNotOverlapOwnNode(doc: org.w3c.dom.Document, fixture: String) {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .forEach { shape ->
                val id = shape.getAttribute("bpmnElement")
                val sb = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return@forEach
                val lbl = shape.getElementsByTagNameNS(DI_NS, "BPMNLabel").item(0) as? Element ?: return@forEach
                val lb = lbl.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element ?: return@forEach
                val shapeY = sb.getAttribute("y").toDoubleOrNull() ?: return@forEach
                val shapeH = sb.getAttribute("height").toDoubleOrNull() ?: return@forEach
                val labelY = lb.getAttribute("y").toDoubleOrNull() ?: return@forEach
                val shapeBottom = shapeY + shapeH
                // Label must start at or below shape bottom — overlap means label top < shape bottom
                assertTrue(
                    labelY >= shapeBottom - 1.0,
                    "[$fixture] Shape '$id' label (top=$labelY) overlaps its own node (bottom=$shapeBottom)",
                )
            }
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
