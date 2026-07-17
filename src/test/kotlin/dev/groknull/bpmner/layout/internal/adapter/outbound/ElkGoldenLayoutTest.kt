/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import org.xmlunit.assertj.XmlAssert
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression oracle over the full 21-fixture corpus.
 *
 * For each committed expected layout under `layout-fixtures/`, asserts byte-identical engine output.
 * Any coordinate change must be reviewed and re-committed before this test will pass again.
 *
 * Also asserts cross-cutting geometry invariants (positive bounds, ≥2 waypoints,
 * labels below nodes) and determinism for all 21 fixtures.
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
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
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
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
        ],
    )
    @Suppress("CyclomaticComplexMethod")
    fun `all 21 corpus fixtures satisfy geometry invariants`(fixture: String) {
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

    @ParameterizedTest(name = "collaboration plane binds to Collaboration: {0}")
    @ValueSource(
        strings = [
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
        ],
    )
    fun `collaboration fixture plane bpmnElement references the Collaboration`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val result = layouter.layout(input)

        // Look up the actual Collaboration element ID from the input rather than testing by name convention.
        val inputDoc = LayoutDiInspector.parse(input)
        val collabElements = inputDoc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/MODEL", "collaboration")
        assertEquals(1, collabElements.length, "[$fixture] input must have exactly one collaboration")
        val expectedCollabId = (collabElements.item(0) as Element).getAttribute("id")
        assertTrue(expectedCollabId.isNotBlank(), "[$fixture] input collaboration must have an id")

        val outDoc = LayoutDiInspector.parse(result)
        val planes = outDoc.getElementsByTagNameNS(DI_NS, "BPMNPlane")
        assertEquals(1, planes.length, "[$fixture] must have exactly one BPMNPlane")
        val plane = planes.item(0) as Element
        val bpmnElement = plane.getAttribute("bpmnElement")
        assertEquals(
            expectedCollabId,
            bpmnElement,
            "[$fixture] BPMNPlane bpmnElement must reference the Collaboration ('$expectedCollabId')",
        )
    }

    @ParameterizedTest(name = "participant shapes present: {0}")
    @ValueSource(
        strings = [
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
        ],
    )
    fun `collaboration fixture has BPMNShape for each participant`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val result = layouter.layout(input)
        val inputDoc = LayoutDiInspector.parse(input)
        val resultDoc = LayoutDiInspector.parse(result)

        val bpmnNs = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        val participants = inputDoc.getElementsByTagNameNS(bpmnNs, "participant")
        val shapes = resultDoc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        val shapeElementIds = (0 until shapes.length).map {
            (shapes.item(it) as Element).getAttribute("bpmnElement")
        }.toSet()

        for (i in 0 until participants.length) {
            val participantId = (participants.item(i) as Element).getAttribute("id")
            assertTrue(
                participantId in shapeElementIds,
                "[$fixture] must have BPMNShape for participant '$participantId'",
            )
        }
    }

    @ParameterizedTest(name = "message flow edges present: {0}")
    @ValueSource(
        strings = [
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
        ],
    )
    fun `collaboration fixture has BPMNEdge for each message flow`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val result = layouter.layout(input)
        val inputDoc = LayoutDiInspector.parse(input)
        val resultDoc = LayoutDiInspector.parse(result)

        val bpmnNs = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        val msgFlows = inputDoc.getElementsByTagNameNS(bpmnNs, "messageFlow")
        val edges = resultDoc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
        val edgeElementIds = (0 until edges.length).map {
            (edges.item(it) as Element).getAttribute("bpmnElement")
        }.toSet()

        for (i in 0 until msgFlows.length) {
            val mfId = (msgFlows.item(i) as Element).getAttribute("id")
            assertTrue(
                mfId in edgeElementIds,
                "[$fixture] must have BPMNEdge for messageFlow '$mfId'",
            )
        }
    }

    @ParameterizedTest(name = "bioc colours survive DI-merge: collab-bioc.bpmn")
    @ValueSource(strings = ["collab-bioc"])
    fun `DI-merge preserves bioc colour attributes on re-laid-out shapes`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val result = layouter.layout(input)
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bioc:stroke]").exist()
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bioc:fill]").exist()
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

    @Suppress("ReturnCount")
    private fun assertShapeLabelBelow(shape: Element, fixture: String) {
        val id = shape.getAttribute("bpmnElement")
        // Participants and lanes use a left-side header band label (isHorizontal=true), not a
        // below-node label. Skip them so the "labels below nodes" invariant only applies to
        // flow-node shapes where below-placement is the convention.
        if (shape.getAttribute("isHorizontal") == "true") return
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
            "collab-lanes",
            "collab-two-pools",
            "collab-blackbox",
            "collab-msg-endpoint",
            "collab-msg-label",
            "collab-subprocess",
            "collab-bioc",
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
                // Participants and lanes are horizontal pool containers — skip from overlap check.
                if (shape.getAttribute("isHorizontal") == "true") return@mapNotNull null
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
                // Skip participants and lanes — their labels are in the left-side header band,
                // not below the shape. This is correct BPMN-DI convention for horizontal pools.
                if (shape.getAttribute("isHorizontal") == "true") return@forEach
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

    private fun assertXml(xml: String): XmlAssert = XmlAssert.assertThat(xml)
        .withNamespaceContext(
            mapOf(
                "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
                "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
                "dc" to "http://www.omg.org/spec/DD/20100524/DC",
                "di" to "http://www.omg.org/spec/DD/20100524/DI",
                "bioc" to "http://bpmn.io/schema/bpmn/biocolor/1.0",
            ),
        )

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
