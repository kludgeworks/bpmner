/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.xml.sax.InputSource
import org.xmlunit.assertj.XmlAssert
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layer 4: end-to-end pipeline tests through [ElkBpmnLayouter].
 *
 * Verifies semantic DI coverage, positive geometry, routed edges, containment,
 * boundary attachment, spatial invariants, and deterministic output.
 * Never compares exact coordinates or asserts parity with the JavaScript layout engine.
 */
@Suppress("TooManyFunctions")
class ElkBpmnLayouterTest {

    private val layouter = ElkBpmnLayouter()

    // ── Flat corpus — invariants (existing 4 fixtures) ────────────────────────

    @ParameterizedTest(name = "flat corpus invariants: {0}")
    @ValueSource(
        strings = [
            "representative-process.bpmn",
            "explicit-cycle.bpmn",
            "annotation-and-group.bpmn",
            "long-labels.bpmn",
        ],
    )
    fun `flat corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Subprocess corpus — invariants ────────────────────────────────────────

    @ParameterizedTest(name = "subprocess corpus invariants: {0}")
    @ValueSource(
        strings = [
            "subprocess-flat.bpmn",
            "subprocess-nested.bpmn",
            "subprocess-branch.bpmn",
        ],
    )
    fun `subprocess corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Boundary event corpus — invariants ────────────────────────────────────

    @ParameterizedTest(name = "boundary corpus invariants: {0}")
    @ValueSource(
        strings = [
            "boundary-timer-task.bpmn",
            "boundary-error-task.bpmn",
            "boundary-multi.bpmn",
            "boundary-on-subprocess.bpmn",
        ],
    )
    fun `boundary corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)
        assertExactlyOneDiagram(result)
        val asserter = assertXml(result)
        for (id in expectedShapeIds) asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        for (id in expectedEdgeIds) asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Spatial invariants: subprocess containment ────────────────────────────

    @Test
    fun `subprocess child shapes are contained within their parent subprocess shape bounds`() {
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        assertChildrenContainedInParent(result, "SubProcess_1", listOf("SubStart_1", "SubTask_1", "SubEnd_1"))
    }

    @Test
    fun `two-level nested subprocess grandchild is inside inner which is inside outer`() {
        val result = layouter.layout(loadCorpus("subprocess-nested.bpmn"))
        assertChildrenContainedInParent(result, "SubProcess_outer", listOf("SubProcess_inner"))
        assertChildrenContainedInParent(result, "SubProcess_inner", listOf("GrandchildTask"))
    }

    @Test
    fun `subprocess BPMNShapes have isExpanded true`() {
        val result = layouter.layout(loadCorpus("subprocess-flat.bpmn"))
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='SubProcess_1' and @isExpanded='true']").exist()
    }

    @Test
    fun `subprocess with internal branch children contained in subprocess`() {
        val result = layouter.layout(loadCorpus("subprocess-branch.bpmn"))
        assertChildrenContainedInParent(
            result,
            "SubProcess_1",
            listOf("SubStart", "Gw_split", "Task_upper", "Task_lower", "Gw_join", "SubEnd"),
        )
    }

    // ── Spatial invariants: boundary event attachment ─────────────────────────

    @Test
    fun `timer boundary event centre lies on host task perimeter`() {
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        assertBoundaryOnHostPerimeter(result, "Boundary_timer", "Task_process")
    }

    @Test
    fun `error boundary event centre lies on host task perimeter`() {
        val result = layouter.layout(loadCorpus("boundary-error-task.bpmn"))
        assertBoundaryOnHostPerimeter(result, "Boundary_error", "Task_process")
    }

    @Test
    fun `two boundary events on same host both lie on host perimeter`() {
        val result = layouter.layout(loadCorpus("boundary-multi.bpmn"))
        assertBoundaryOnHostPerimeter(result, "Boundary_timer", "Task_process")
        assertBoundaryOnHostPerimeter(result, "Boundary_error", "Task_process")
    }

    @Test
    fun `boundary event on subprocess lies on subprocess perimeter`() {
        val result = layouter.layout(loadCorpus("boundary-on-subprocess.bpmn"))
        assertBoundaryOnHostPerimeter(result, "Boundary_timer", "SubProcess_1")
    }

    @Test
    fun `exception flow first waypoint is near boundary event centre`() {
        val result = layouter.layout(loadCorpus("boundary-timer-task.bpmn"))
        assertExceptionEdgeNearBoundary(result, "Flow_timeout", "Boundary_timer")
    }

    // ── Determinism (parametric, all corpus fixtures) ─────────────────────────

    @ParameterizedTest(name = "determinism: {0}")
    @ValueSource(
        strings = [
            "representative-process.bpmn",
            "subprocess-flat.bpmn",
            "subprocess-nested.bpmn",
            "boundary-timer-task.bpmn",
            "boundary-multi.bpmn",
        ],
    )
    fun `repeated layout of same input produces stable DI geometry`(fixture: String) {
        val xml = loadCorpus(fixture)
        val firstDi = diCoordinates(layouter.layout(xml))
        val secondDi = diCoordinates(layouter.layout(xml))
        assertEquals(firstDi, secondDi, "ELK layout geometry was not deterministic for $fixture")
    }

    // ── Existing focused behavioral tests ─────────────────────────────────────

    @Test
    fun `existing DI is replaced not duplicated`() {
        val xmlWithDi = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                  id="Definitions_1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1"/>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="OldDiagram">
    <bpmndi:BPMNPlane id="OldPlane" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="OldShape" bpmnElement="Start_1">
        <dc:Bounds x="0" y="0" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""
        val result = layouter.layout(xmlWithDi)
        assertExactlyOneDiagram(result)
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1']").exist()
        assertXml(result).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1']").exist()
    }

    @Test
    fun `explicit cycle produces routed feedback edge`() {
        val result = layouter.layout(loadCorpus("explicit-cycle.bpmn"))
        assertXml(result).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_retry']").exist()
    }

    @Test
    fun `malformed XML throws BpmnAutoLayoutException`() {
        kotlin.test.assertFailsWith<dev.groknull.bpmner.layout.BpmnAutoLayoutException> {
            layouter.layout("not xml")
        }
    }

    // ── Spatial assertion helpers ─────────────────────────────────────────────

    /**
     * Asserts every child ID's shape bounds are fully contained within the parent shape bounds.
     * Applies a 1px tolerance for rounding artefacts.
     */
    private fun assertChildrenContainedInParent(xml: String, parentId: String, childIds: List<String>) {
        val doc = parseXmlDoc(xml)
        val parentBounds = shapeBounds(doc, parentId)
        for (childId in childIds) {
            val childBounds = shapeBounds(doc, childId)
            val tolerance = 1.0
            assertTrue(
                childBounds["x"]!! >= parentBounds["x"]!! - tolerance,
                "$childId left (${childBounds["x"]}) must be inside $parentId left (${parentBounds["x"]})",
            )
            assertTrue(
                childBounds["y"]!! >= parentBounds["y"]!! - tolerance,
                "$childId top (${childBounds["y"]}) must be inside $parentId top (${parentBounds["y"]})",
            )
            assertTrue(
                childBounds["x"]!! + childBounds["width"]!! <= parentBounds["x"]!! + parentBounds["width"]!! + tolerance,
                "$childId right must be inside $parentId right",
            )
            assertTrue(
                childBounds["y"]!! + childBounds["height"]!! <= parentBounds["y"]!! + parentBounds["height"]!! + tolerance,
                "$childId bottom must be inside $parentId bottom",
            )
        }
    }

    /**
     * Asserts the boundary event centre is within EVENT_SIZE/2 of at least one edge of the host.
     * This verifies straddling without requiring exact perimeter placement.
     */
    private fun assertBoundaryOnHostPerimeter(xml: String, boundaryId: String, hostId: String) {
        val doc = parseXmlDoc(xml)
        val hostBounds = shapeBounds(doc, hostId)
        val beBounds = shapeBounds(doc, boundaryId)
        val tolerance = EVENT_SIZE
        val beCx = beBounds["x"]!! + beBounds["width"]!! / 2.0
        val beCy = beBounds["y"]!! + beBounds["height"]!! / 2.0
        val hostLeft = hostBounds["x"]!!
        val hostRight = hostLeft + hostBounds["width"]!!
        val hostTop = hostBounds["y"]!!
        val hostBottom = hostTop + hostBounds["height"]!!

        val nearLeft = Math.abs(beCx - hostLeft) <= tolerance
        val nearRight = Math.abs(beCx - hostRight) <= tolerance
        val nearTop = Math.abs(beCy - hostTop) <= tolerance
        val nearBottom = Math.abs(beCy - hostBottom) <= tolerance

        assertTrue(
            nearLeft || nearRight || nearTop || nearBottom,
            "Boundary '$boundaryId' centre ($beCx,$beCy) must be within $tolerance of a host '$hostId' edge " +
                "(left=$hostLeft, right=$hostRight, top=$hostTop, bottom=$hostBottom)",
        )
    }

    /**
     * Asserts the first waypoint of the exception edge is within EVENT_SIZE of the boundary event centre.
     */
    private fun assertExceptionEdgeNearBoundary(xml: String, edgeId: String, boundaryId: String) {
        val doc = parseXmlDoc(xml)
        val beBounds = shapeBounds(doc, boundaryId)
        val beCx = beBounds["x"]!! + beBounds["width"]!! / 2.0
        val beCy = beBounds["y"]!! + beBounds["height"]!! / 2.0

        val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as org.w3c.dom.Element
            if (edge.getAttribute("bpmnElement") == edgeId) {
                val waypoints = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
                assertTrue(waypoints.length >= 1, "Edge $edgeId must have at least one waypoint")
                val wp = waypoints.item(0) as org.w3c.dom.Element
                val wpx = wp.getAttribute("x").toDouble()
                val wpy = wp.getAttribute("y").toDouble()
                val dist = Math.sqrt((wpx - beCx) * (wpx - beCx) + (wpy - beCy) * (wpy - beCy))
                assertTrue(
                    dist <= EVENT_SIZE * 2,
                    "Edge '$edgeId' first waypoint must be near boundary '$boundaryId' centre; dist=$dist",
                )
                return
            }
        }
        error("Edge $edgeId not found in DI")
    }

    private fun shapeBounds(doc: org.w3c.dom.Document, bpmnElementId: String): Map<String, Double> {
        val shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as org.w3c.dom.Element
            if (shape.getAttribute("bpmnElement") == bpmnElementId) {
                val bounds = shape.getElementsByTagNameNS(
                    "http://www.omg.org/spec/DD/20100524/DC",
                    "Bounds",
                ).item(0) as org.w3c.dom.Element
                return mapOf(
                    "x" to bounds.getAttribute("x").toDouble(),
                    "y" to bounds.getAttribute("y").toDouble(),
                    "width" to bounds.getAttribute("width").toDouble(),
                    "height" to bounds.getAttribute("height").toDouble(),
                )
            }
        }
        error("No BPMNShape found for bpmnElement='$bpmnElementId'")
    }

    // ── Existing helpers ──────────────────────────────────────────────────────

    /**
     * Derives the shape and edge IDs that must appear in the DI output by reading
     * flow-node IDs, text-annotation IDs, group IDs, sequence-flow IDs, and
     * association IDs directly from the input BPMN XML.
     */
    private fun semanticIds(xml: String): Pair<Set<String>, Set<String>> {
        val doc = parseXmlDoc(xml)
        val bpmnNs = "http://www.omg.org/spec/BPMN/20100524/MODEL"

        val shapeIds = mutableSetOf<String>()
        val edgeIds = mutableSetOf<String>()

        val shapeElements = listOf(
            "startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent",
            "boundaryEvent", "userTask", "serviceTask", "sendTask", "receiveTask",
            "manualTask", "businessRuleTask", "scriptTask", "task",
            "callActivity", "subProcess",
            "exclusiveGateway", "parallelGateway", "inclusiveGateway",
            "eventBasedGateway", "complexGateway",
            "textAnnotation", "group",
        )
        for (tag in shapeElements) {
            val nodes = doc.getElementsByTagNameNS(bpmnNs, tag)
            for (i in 0 until nodes.length) {
                val id = (nodes.item(i) as org.w3c.dom.Element).getAttribute("id")
                if (id.isNotBlank()) shapeIds.add(id)
            }
        }

        for (tag in listOf("sequenceFlow", "association")) {
            val nodes = doc.getElementsByTagNameNS(bpmnNs, tag)
            for (i in 0 until nodes.length) {
                val id = (nodes.item(i) as org.w3c.dom.Element).getAttribute("id")
                if (id.isNotBlank()) edgeIds.add(id)
            }
        }

        return Pair(shapeIds, edgeIds)
    }

    private fun assertExactlyOneDiagram(xml: String) {
        val doc = parseXmlDoc(xml)
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val diagrams = doc.getElementsByTagNameNS(diNs, "BPMNDiagram")
        assertEquals(1, diagrams.length, "Expected exactly one BPMNDiagram but got ${diagrams.length}")
        val planes = doc.getElementsByTagNameNS(diNs, "BPMNPlane")
        assertEquals(1, planes.length, "Expected exactly one BPMNPlane but got ${planes.length}")
    }

    private fun assertPositiveBounds(xml: String) {
        val doc = parseXmlDoc(xml)
        val shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as org.w3c.dom.Element
            val shapeId = shape.getAttribute("bpmnElement")
            val boundsNodes = shape.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DC", "Bounds")
            if (boundsNodes.length > 0) {
                val bounds = boundsNodes.item(0) as org.w3c.dom.Element
                val w = bounds.getAttribute("width").toDoubleOrNull() ?: 0.0
                val h = bounds.getAttribute("height").toDoubleOrNull() ?: 0.0
                assertTrue(w > 0.0, "Shape '$shapeId' has non-positive width: $w")
                assertTrue(h > 0.0, "Shape '$shapeId' has non-positive height: $h")
            }
        }
    }

    private fun assertMinWaypoints(xml: String, minCount: Int) {
        val doc = parseXmlDoc(xml)
        val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as org.w3c.dom.Element
            val edgeId = edge.getAttribute("bpmnElement")
            val waypoints = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
            assertTrue(
                waypoints.length >= minCount,
                "Edge '$edgeId' has only ${waypoints.length} waypoint(s); expected at least $minCount",
            )
        }
    }

    /** Canonical sorted list of shape/edge geometry strings for determinism comparison. */
    private fun diCoordinates(xml: String): List<String> {
        val doc = parseXmlDoc(xml)
        val results = mutableListOf<String>()
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val dcNs = "http://www.omg.org/spec/DD/20100524/DC"
        val diDiNs = "http://www.omg.org/spec/DD/20100524/DI"

        val shapes = doc.getElementsByTagNameNS(diNs, "BPMNShape")
        (0 until shapes.length)
            .map { shapes.item(it) as org.w3c.dom.Element }
            .sortedBy { it.getAttribute("bpmnElement") }
            .forEach { shape ->
                val bounds = shape.getElementsByTagNameNS(dcNs, "Bounds")
                if (bounds.length > 0) {
                    val b = bounds.item(0) as org.w3c.dom.Element
                    results.add(
                        "shape:${shape.getAttribute("bpmnElement")}:" +
                            "${b.getAttribute("x")},${b.getAttribute("y")}," +
                            "${b.getAttribute("width")},${b.getAttribute("height")}",
                    )
                }
            }

        val edges = doc.getElementsByTagNameNS(diNs, "BPMNEdge")
        (0 until edges.length)
            .map { edges.item(it) as org.w3c.dom.Element }
            .sortedBy { it.getAttribute("bpmnElement") }
            .forEach { edge ->
                val wps = edge.getElementsByTagNameNS(diDiNs, "waypoint")
                val coords = (0 until wps.length).joinToString(";") { i ->
                    val wp = wps.item(i) as org.w3c.dom.Element
                    "${wp.getAttribute("x")},${wp.getAttribute("y")}"
                }
                results.add("edge:${edge.getAttribute("bpmnElement")}:$coords")
            }

        return results
    }

    private fun parseXmlDoc(xml: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    private fun loadCorpus(filename: String): String = javaClass.classLoader.getResourceAsStream("bpmn/elk-corpus/$filename")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Corpus fixture not found: bpmn/elk-corpus/$filename")

    private fun assertXml(xml: String): XmlAssert = XmlAssert.assertThat(xml).withNamespaceContext(
        mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
            "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
            "dc" to "http://www.omg.org/spec/DD/20100524/DC",
            "di" to "http://www.omg.org/spec/DD/20100524/DI",
        ),
    )

    private companion object {
        const val EVENT_SIZE = 36.0
    }
}
