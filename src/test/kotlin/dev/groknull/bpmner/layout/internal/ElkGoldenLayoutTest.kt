/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import org.xmlunit.assertj.XmlAssert
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test over the full 25-fixture set.
 *
 * For each committed expected layout under `layout-fixtures/`, asserts byte-identical engine output.
 * Any coordinate change must be reviewed and re-committed before this test will pass again.
 *
 * Also asserts cross-cutting geometry invariants (positive bounds, ≥2 waypoints,
 * labels below nodes) and determinism for all 25 fixtures.
 */
@Suppress("TooManyFunctions")
class ElkGoldenLayoutTest {

    private val layouter = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }

    companion object {
        private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
        private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"
        private const val DD_NS = "http://www.omg.org/spec/DD/20100524/DI"

        /** Shapes at or above this area (200×200) are containers (subprocess/lane/participant). */
        private const val CONTAINER_AREA = 40_000.0

        /**
         * Known label/edge-waypoint overlaps (fixture, label owner id, edge id). Each pair traces
         * to one of two causes: a hand-routed message flow (or, since group E, an ELK
         * comment-attached `Association`) exits its sole-incident source task at the same x/y
         * `NodeLabelPlacement.outsideBottomCenter()` centres that task's own label under, or an
         * ELK-routed sequence flow's south-port exit threads through its own gateway's placed
         * label before turning. `miwg-c2-four-pools`'s nine rows are the same first cause at
         * higher volume — five simultaneous hand-routed message flows crossing a four-participant
         * stack densely enough that fanning one pair's bend height (`P5`) still leaves several
         * labels under a neighbouring flow's or task's own footprint.
         * [assertLabelsClearEdgeGeometry] asserts this set is *exact* against every fixture in the
         * corpus — a new collision fails immediately, and a declared pair that stops reproducing
         * must be removed.
         */
        private val LABEL_EDGE_OVERLAP_EXCEPTIONS = setOf(
            Triple("collab-two-pools", "Task_order", "MsgFlow_1"),
            Triple("collab-two-pools", "Task_pay", "MsgFlow_2"),
            Triple("collab-blackbox", "Task_receive", "MsgFlow_in"),
            Triple("collab-blackbox", "Task_send", "MsgFlow_out"),
            Triple("collab-msg-endpoint", "Task_A1", "MsgFlow_1"),
            Triple("collab-msg-endpoint", "Task_A2", "MsgFlow_2"),
            Triple("collab-msg-label", "Task_receive_pkg", "MsgFlow_notify"),
            Triple("collab-msg-label", "Task_track", "MsgFlow_order"),
            Triple("collab-subprocess", "Task_finalize", "MsgFlow_2"),
            Triple("collab-subprocess", "Task_prepare", "MsgFlow_1"),
            Triple("collab-bioc", "Task_1", "MsgFlow_1"),
            Triple("annotation-and-group", "Task_1", "Assoc_1"),
            Triple("collab-lanes", "Gw_split", "Flow_3"),
            Triple("collab-lanes", "Task_pack", "Flow_5"),
            Triple("collab-lanes-loopback", "Gw_check", "Flow_ok"),
            Triple("miwg-c2-four-pools", "End_warehouse", "MsgFlow_delivered"),
            Triple("miwg-c2-four-pools", "Task_confirm_order", "MsgFlow_confirm"),
            Triple("miwg-c2-four-pools", "Task_confirm_order", "MsgFlow_dispatch"),
            Triple("miwg-c2-four-pools", "Task_place_order", "MsgFlow_order"),
            Triple("miwg-c2-four-pools", "Task_process_order", "MsgFlow_reserve"),
            Triple("miwg-c2-four-pools", "Task_receive_goods", "MsgFlow_delivered"),
            Triple("miwg-c2-four-pools", "Task_reserve_stock", "MsgFlow_dispatch"),
            Triple("miwg-c2-four-pools", "Boundary_fulfil_error", "MsgFlow_confirm"),
            Triple("miwg-c2-four-pools", "MsgFlow_reserve", "MsgFlow_delivered"),
        )

        @JvmStatic
        fun fixtures() = LAYOUT_CORPUS_FIXTURES
    }

    @ParameterizedTest(name = "matches committed expected: {0}")
    @MethodSource("fixtures")
    fun `engine output matches committed expected`(fixture: String) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val expected = load("layout-fixtures/$fixture.expected.bpmn")
        val actual = layouter.layout(input)
        assertEquals(
            expected,
            actual,
            "Engine output for '$fixture' does not match the committed expected output. " +
                "If the layout changed intentionally, run generate_candidate_goldens, " +
                "review in bpmn-js, and re-bless the expected output before updating this test.",
        )
    }

    @ParameterizedTest(name = "geometry invariants: {0}")
    @MethodSource("fixtures")
    @Suppress("CyclomaticComplexMethod")
    fun `all 25 layout fixtures satisfy geometry invariants`(fixture: String) {
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

    @ParameterizedTest(name = "labels clear labels and shapes: {0}")
    @MethodSource("fixtures")
    fun `named labels do not overlap other labels or shapes`(fixture: String) {
        val doc = LayoutDiInspector.parse(layouter.layout(load("layout-fixtures/$fixture.bpmn")))
        assertLabelsClearOtherDiGeometry(doc, fixture)
        assertLabelsClearEdgeGeometry(doc, fixture)
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
            "collab-lanes-loopback",
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
            "collab-lanes-loopback",
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
    @MethodSource("fixtures")
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
        val headerOwners = (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .filter { it.getAttribute("isHorizontal") == "true" }
            .mapTo(mutableSetOf()) { it.getAttribute("bpmnElement") }

        // Non-container: area < 40000 (200×200). Subprocesses (e.g. 300×200 = 60000) contain
        // their children by design. Boundary events (area = 36×36 ≈ 1296) straddle their host by
        // design — both are excluded. Participants/lanes are horizontal pool containers — excluded too.
        val nonContainer = extractShapeRects(doc)
            .filter { it.id !in boundaryEventIds && it.id !in headerOwners && it.w * it.h < CONTAINER_AREA }

        val overlaps = overlappingPairs(nonContainer)
        assertTrue(
            overlaps.isEmpty(),
            overlaps.joinToString("; ") { (a, b) -> "[$fixture] Non-container shapes '${a.id}' and '${b.id}' overlap" },
        )
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

    /** `isHorizontal` shapes (participants/lanes) use a header-band label, not a below-node one. */
    private fun headerOwnerIds(doc: org.w3c.dom.Document): Set<String> {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        return (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .filter { it.getAttribute("isHorizontal") == "true" }
            .mapTo(mutableSetOf()) { it.getAttribute("bpmnElement") }
    }

    private fun boundsRect(ownerId: String, bounds: Element?): DiRect? {
        val x = bounds?.getAttribute("x")?.toDoubleOrNull() ?: return null
        val y = bounds.getAttribute("y").toDoubleOrNull() ?: return null
        val w = bounds.getAttribute("width").toDoubleOrNull() ?: return null
        val h = bounds.getAttribute("height").toDoubleOrNull() ?: return null
        return DiRect(ownerId, x, y, w, h)
    }

    /** Every non-header `BPMNLabel`'s bounds, keyed by its owning shape's `bpmnElement` id. */
    private fun extractLabelRects(doc: org.w3c.dom.Document, headerOwners: Set<String>): List<DiRect> {
        val labels = doc.getElementsByTagNameNS(DI_NS, "BPMNLabel")
        return (0 until labels.length)
            .map { labels.item(it) as Element }
            .mapNotNull { label ->
                val owner = label.parentNode as? Element ?: return@mapNotNull null
                val ownerId = owner.getAttribute("bpmnElement")
                if (ownerId in headerOwners) return@mapNotNull null
                boundsRect(ownerId, label.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element)
            }
    }

    /** Checks labels against all non-header labels and shapes, except the label owner's own shape. */
    private fun assertLabelsClearOtherDiGeometry(doc: org.w3c.dom.Document, fixture: String) {
        fun overlaps(first: DiRect, second: DiRect): Boolean {
            val overlapX = minOf(first.right, second.right) - maxOf(first.x, second.x)
            val overlapY = minOf(first.bottom, second.bottom) - maxOf(first.y, second.y)
            return overlapX >= 1.0 && overlapY >= 1.0
        }

        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        val headerOwners = headerOwnerIds(doc)
        val shapeRects = (0 until shapes.length)
            .map { shapes.item(it) as Element }
            .filter { it.getAttribute("bpmnElement") !in headerOwners }
            .mapNotNull { shape ->
                boundsRect(shape.getAttribute("bpmnElement"), shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as? Element)
            }
            // Containers (subprocesses) are excluded: a member's label sitting inside its own
            // subprocess's bounds is the normal nesting relationship, not an overlap defect —
            // the same convention assertNoTopLevelShapeOverlap already applies to shapes.
            .filter { it.w * it.h < CONTAINER_AREA }
        val labelRects = extractLabelRects(doc, headerOwners)

        for (i in labelRects.indices) {
            val label = labelRects[i]
            labelRects.drop(i + 1).forEach { other ->
                assertTrue(!overlaps(label, other), "[$fixture] labels '${label.id}' and '${other.id}' overlap")
            }
            shapeRects.filter { it.id != label.id }.forEach { shape ->
                assertTrue(!overlaps(label, shape), "[$fixture] label '${label.id}' overlaps shape '${shape.id}'")
            }
        }
    }

    /**
     * Checks each named label's rect against every `BPMNEdge`'s waypoint polyline, corpus-wide.
     * The observed overlap set must exactly equal [LABEL_EDGE_OVERLAP_EXCEPTIONS] for this
     * fixture — a new collision fails the build, and so does a declared one that disappears
     * (forcing the exception set to shrink deliberately rather than rot).
     */
    private fun assertLabelsClearEdgeGeometry(doc: org.w3c.dom.Document, fixture: String) {
        val labelRects = extractLabelRects(doc, headerOwnerIds(doc))
        val edges = extractEdges(doc)

        val observed = labelRects.flatMap { label ->
            // Exclude an edge's own label against its own waypoints: an edge label sits on its
            // own path by design (the same convention that excludes a shape's own label above).
            edges.filter { edge ->
                edge.id != label.id &&
                    (0 until edge.waypoints.size - 1).any { i ->
                        segmentIntersectsRect(edge.waypoints[i], edge.waypoints[i + 1], label)
                    }
            }.map { edge -> Triple(fixture, label.id, edge.id) }
        }.toSet()
        val expected = LABEL_EDGE_OVERLAP_EXCEPTIONS.filterTo(mutableSetOf()) { it.first == fixture }

        assertEquals(
            expected,
            observed,
            "[$fixture] label/edge-waypoint overlaps do not match the declared exception set. " +
                "A new collision must be fixed, not declared; a declared collision that no longer " +
                "reproduces must be removed from LABEL_EDGE_OVERLAP_EXCEPTIONS.",
        )
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
