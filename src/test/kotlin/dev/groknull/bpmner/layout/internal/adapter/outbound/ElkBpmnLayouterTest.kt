/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.xmlunit.assertj.XmlAssert
import kotlin.test.assertEquals

/**
 * Verifies the test-internal ELK layout path over the flat-geometry corpus.
 *
 * Assertions target semantic invariants (ID coverage, positive bounds, waypoint order,
 * label clearance, determinism) — never exact coordinates (AD-557-05, plan §7).
 *
 * This class exercises ElkBpmnLayouter directly; it is NOT a Spring boot test and
 * does not involve BpmnLayoutPort or BpmnLayoutService (those remain the production path).
 */
class ElkBpmnLayouterTest {

    private val layouter = ElkBpmnLayouter()

    // ── Corpus: each geometry class laid out successfully ────────────────────────────────────────

    @ParameterizedTest(name = "corpus: {0}")
    @ValueSource(
        strings = [
            "linear-flow.bpmn",
            "exclusive-branch.bpmn",
            "parallel-branch.bpmn",
            "inclusive-branch.bpmn",
            "event-based-gateway.bpmn",
            "long-labels.bpmn",
            "call-activity.bpmn",
            "explicit-cycle.bpmn",
            "annotation-and-group.bpmn",
        ],
    )
    fun `corpus fixture produces complete and valid DI`(fixture: String) {
        val xml = loadCorpus(fixture)
        val result = layouter.layout(xml)

        val asserter = assertXml(result)

        // 1. Exactly one BPMNDiagram and one BPMNPlane
        asserter.nodesByXPath("//bpmndi:BPMNDiagram").exist()
        asserter.nodesByXPath("//bpmndi:BPMNPlane").exist()
        assertExactlyOneDiagram(result)

        // 2. At least one BPMNShape
        asserter.nodesByXPath("//bpmndi:BPMNShape").exist()
    }

    @Test
    fun `linear flow has shape and edge for every semantic element`() {
        val xml = loadCorpus("linear-flow.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        // Flow node shapes
        listOf("Start_1", "Task_1", "Task_2", "End_1").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        }

        // Sequence flow edges
        listOf("Flow_1", "Flow_2", "Flow_3").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        }
    }

    @Test
    fun `exclusive branch has shape for every node and edge for every flow`() {
        val xml = loadCorpus("exclusive-branch.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        listOf("Start_1", "Gw_split", "Task_yes", "Task_no", "Gw_join", "End_1").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        }

        listOf("Flow_1", "Flow_yes", "Flow_no", "Flow_join_yes", "Flow_join_no", "Flow_end").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        }
    }

    @Test
    fun `parallel branch has shapes and edges`() {
        val xml = loadCorpus("parallel-branch.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        listOf("Gw_fork", "Task_a", "Task_b", "Gw_join").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        }

        listOf("Flow_a", "Flow_b", "Flow_join_a", "Flow_join_b").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']").exist()
        }
    }

    @Test
    fun `event-based gateway produces shapes for intermediate catch events`() {
        val xml = loadCorpus("event-based-gateway.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        listOf("Gw_event", "Timer_1", "Msg_1", "End_timeout", "End_approved").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        }
    }

    @Test
    fun `long labels fixture produces shapes with positive bounds`() {
        val xml = loadCorpus("long-labels.bpmn")
        val result = layouter.layout(xml)

        // All shape bounds must be positive (non-zero width and height)
        assertPositiveBounds(result)
    }

    @Test
    fun `explicit cycle is laid out without error`() {
        val xml = loadCorpus("explicit-cycle.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        // Loop-back edge must have DI
        asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_retry']").exist()
    }

    @Test
    fun `annotation and group fixture produces DI for annotation shape and association edge`() {
        val xml = loadCorpus("annotation-and-group.bpmn")
        val result = layouter.layout(xml)
        val asserter = assertXml(result)

        // Text annotation shape
        asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Anno_1']").exist()
        // Association edge
        asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Assoc_1']").exist()
        // Flow nodes still have shapes
        listOf("Task_1", "Task_2").forEach { id ->
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']").exist()
        }
    }

    @Test
    fun `call activity receives a shape`() {
        val xml = loadCorpus("call-activity.bpmn")
        val result = layouter.layout(xml)
        assertXml(result).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Call_1']").exist()
    }

    // ── Sequence-flow edges have ≥ 2 waypoints ────────────────────────────────────────────────────

    @Test
    fun `all sequence-flow edges have at least two waypoints`() {
        val xml = loadCorpus("exclusive-branch.bpmn")
        val result = layouter.layout(xml)
        assertMinWaypoints(result, minCount = 2)
    }

    @Test
    fun `cycle edges have at least two waypoints`() {
        val xml = loadCorpus("explicit-cycle.bpmn")
        val result = layouter.layout(xml)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Old DI is replaced, not duplicated ────────────────────────────────────────────────────────

    @Test
    fun `existing DI in input is replaced not duplicated`() {
        // Input has an existing BPMNDiagram; output must have exactly one
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
      <bpmndi:BPMNShape id="OldShape_Start" bpmnElement="Start_1">
        <dc:Bounds x="0" y="0" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>"""

        val result = layouter.layout(xmlWithDi)
        val asserter = assertXml(result)

        assertExactlyOneDiagram(result)
        asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Start_1']").exist()
        asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_1']").exist()
    }

    // ── Determinism: byte-identical output on repeated runs ──────────────────────────────────────

    @ParameterizedTest(name = "determinism: {0}")
    @ValueSource(
        strings = [
            "linear-flow.bpmn",
            "exclusive-branch.bpmn",
            "explicit-cycle.bpmn",
        ],
    )
    fun `repeated layout of same input produces identical DI coordinates`(fixture: String) {
        val xml = loadCorpus(fixture)
        val first = layouter.layout(xml)
        val second = layouter.layout(xml)
        // Compare DI coordinates semantically (not attribute order) to verify determinism.
        // ELK with a fixed seed and stable insertion order must produce numerically stable results.
        val firstDi = extractDiCoordinates(first)
        val secondDi = extractDiCoordinates(second)
        assertEquals(firstDi, secondDi, "Layout DI coordinates of '$fixture' were not deterministic")
    }

    // ── Malformed input ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `throws BpmnAutoLayoutException on malformed XML`() {
        kotlin.test.assertFailsWith<dev.groknull.bpmner.layout.BpmnAutoLayoutException> {
            layouter.layout("not xml")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    /** Extract a canonical list of DI coordinates for determinism comparison. */
    private fun extractDiCoordinates(xml: String): List<String> {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(java.io.StringReader(xml)),
        )
        val results = mutableListOf<String>()
        val diNs = "http://www.omg.org/spec/BPMN/20100524/DI"
        val dcNs = "http://www.omg.org/spec/DD/20100524/DC"
        val diDiNs = "http://www.omg.org/spec/DD/20100524/DI"

        // Shapes: sorted by bpmnElement for deterministic comparison
        val shapes = doc.getElementsByTagNameNS(diNs, "BPMNShape")
        val shapeList = (0 until shapes.length).map { shapes.item(it) as org.w3c.dom.Element }
        for (shape in shapeList.sortedBy { it.getAttribute("bpmnElement") }) {
            val bounds = shape.getElementsByTagNameNS(dcNs, "Bounds")
            if (bounds.length > 0) {
                val b = bounds.item(0) as org.w3c.dom.Element
                val id = shape.getAttribute("bpmnElement")
                val coords = "${b.getAttribute("x")},${b.getAttribute("y")}," +
                    "${b.getAttribute("width")},${b.getAttribute("height")}"
                results.add("shape:$id:$coords")
            }
        }

        // Edges: sorted by bpmnElement
        val edges = doc.getElementsByTagNameNS(diNs, "BPMNEdge")
        val edgeList = (0 until edges.length).map { edges.item(it) as org.w3c.dom.Element }
        for (edge in edgeList.sortedBy { it.getAttribute("bpmnElement") }) {
            val waypoints = edge.getElementsByTagNameNS(diDiNs, "waypoint")
            val wps = (0 until waypoints.length).map { i ->
                val wp = waypoints.item(i) as org.w3c.dom.Element
                "${wp.getAttribute("x")},${wp.getAttribute("y")}"
            }.joinToString(";")
            results.add("edge:${edge.getAttribute("bpmnElement")}:$wps")
        }

        return results
    }

    private fun assertExactlyOneDiagram(xml: String) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(java.io.StringReader(xml)),
        )
        val diagrams = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNDiagram")
        assertEquals(1, diagrams.length, "Expected exactly one BPMNDiagram but got ${diagrams.length}")
        val planes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNPlane")
        assertEquals(1, planes.length, "Expected exactly one BPMNPlane but got ${planes.length}")
    }

    private fun loadCorpus(filename: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("bpmn/elk-corpus/$filename")
            ?: error("Corpus fixture not found: bpmn/elk-corpus/$filename")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun assertXml(xml: String): XmlAssert = XmlAssert.assertThat(xml).withNamespaceContext(
        mapOf(
            "bpmn" to "http://www.omg.org/spec/BPMN/20100524/MODEL",
            "bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI",
            "dc" to "http://www.omg.org/spec/DD/20100524/DC",
            "di" to "http://www.omg.org/spec/DD/20100524/DI",
        ),
    )

    private fun assertPositiveBounds(xml: String) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(java.io.StringReader(xml)),
        )
        val shapes = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as org.w3c.dom.Element
            val shapeId = shape.getAttribute("bpmnElement")
            val boundsNodes = shape.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DC", "Bounds")
            if (boundsNodes.length > 0) {
                val bounds = boundsNodes.item(0) as org.w3c.dom.Element
                val w = bounds.getAttribute("width").toDoubleOrNull() ?: 0.0
                val h = bounds.getAttribute("height").toDoubleOrNull() ?: 0.0
                assert(w > 0.0) { "Shape '$shapeId' has non-positive width: $w" }
                assert(h > 0.0) { "Shape '$shapeId' has non-positive height: $h" }
            }
        }
    }

    private fun assertMinWaypoints(xml: String, minCount: Int) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(
            org.xml.sax.InputSource(java.io.StringReader(xml)),
        )
        val edges = doc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as org.w3c.dom.Element
            val edgeId = edge.getAttribute("bpmnElement")
            val waypoints = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
            assert(waypoints.length >= minCount) {
                "Edge '$edgeId' has only ${waypoints.length} waypoint(s); expected at least $minCount"
            }
        }
    }
}
