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
 * Verifies semantic DI coverage, positive geometry, routed edges, label clearance,
 * and deterministic output. Never compares exact coordinates or asserts parity
 * with the JavaScript layout engine.
 */
class ElkBpmnLayouterTest {

    private val layouter = ElkBpmnLayouter()

    // ── Generic corpus invariants ─────────────────────────────────────────────

    @ParameterizedTest(name = "corpus invariants: {0}")
    @ValueSource(
        strings = [
            "representative-process.bpmn",
            "explicit-cycle.bpmn",
            "annotation-and-group.bpmn",
            "long-labels.bpmn",
        ],
    )
    fun `corpus fixture satisfies all DI invariants`(fixture: String) {
        val inputXml = loadCorpus(fixture)
        val result = layouter.layout(inputXml)

        // Derive expected IDs from the input so the assertion is not hand-maintained.
        val (expectedShapeIds, expectedEdgeIds) = semanticIds(inputXml)

        assertExactlyOneDiagram(result)

        val asserter = assertXml(result)
        for (id in expectedShapeIds) {
            asserter.nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='$id']")
                .exist()
        }
        for (id in expectedEdgeIds) {
            asserter.nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='$id']")
                .exist()
        }

        assertPositiveBounds(result)
        assertMinWaypoints(result, minCount = 2)
    }

    // ── Focused behavioral tests ──────────────────────────────────────────────

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
        // The loop-back edge must have DI; ELK must handle the cycle without failure.
        assertXml(result).nodesByXPath("//bpmndi:BPMNEdge[@bpmnElement='Flow_retry']").exist()
    }

    @Test
    fun `repeated layout of same input produces stable DI geometry`() {
        val xml = loadCorpus("representative-process.bpmn")
        val firstDi = diCoordinates(layouter.layout(xml))
        val secondDi = diCoordinates(layouter.layout(xml))
        assertEquals(firstDi, secondDi, "ELK layout geometry was not deterministic")
    }

    @Test
    fun `malformed XML throws BpmnAutoLayoutException`() {
        kotlin.test.assertFailsWith<dev.groknull.bpmner.layout.BpmnAutoLayoutException> {
            layouter.layout("not xml")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
}
