/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.PlacedLayout
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.xml.sax.InputSource
import org.xmlunit.assertj.XmlAssert
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Layer 3: verifies [ElkToBpmnDiWriter] serialisation using [PlacedLayout] objects with
 * known geometry. Does NOT run the layout engine or placement pass.
 *
 * Every test builds a minimal [PlacedLayout] with hand-crafted geometry, calls
 * [ElkToBpmnDiWriter.write], and asserts the emitted DI — isolating DI serialisation
 * from placement decisions.
 *
 * Also tests that [ElkToBpmnDiWriter.absolutePosition] (delegating to [BpmnPlacementPass])
 * accumulates parent ELK node offsets correctly.
 */
class ElkToBpmnDiWriterTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
        private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"

        /** Minimal BPMN skeleton with a single process but no DI. */
        private fun minimalModel(processBody: String = ""): BpmnModelInstance {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">$processBody</bpmn:process>
</bpmn:definitions>"""
            return Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }

        private fun subprocessModel(): BpmnModelInstance {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:subProcess id="Sub_1">
      <bpmn:startEvent id="Child_1"/>
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>"""
            return Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }

        private fun boundaryModel(): BpmnModelInstance {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:serviceTask id="Task_1"/>
    <bpmn:boundaryEvent id="Boundary_1" attachedToRef="Task_1" cancelActivity="true">
      <bpmn:timerEventDefinition id="TD1"/>
    </bpmn:boundaryEvent>
  </bpmn:process>
</bpmn:definitions>"""
            return Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }

        private fun parseDoc(xml: String) = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))

        private fun boundsOf(xml: String, bpmnElementId: String): Map<String, Double> {
            val doc = parseDoc(xml)
            val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
            for (i in 0 until shapes.length) {
                val shape = shapes.item(i) as org.w3c.dom.Element
                if (shape.getAttribute("bpmnElement") == bpmnElementId) {
                    val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as org.w3c.dom.Element
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

        private fun labelBoundsOf(xml: String, bpmnElementId: String): Map<String, Double>? {
            val doc = parseDoc(xml)
            val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
            for (i in 0 until shapes.length) {
                val shape = shapes.item(i) as org.w3c.dom.Element
                if (shape.getAttribute("bpmnElement") == bpmnElementId) {
                    val labels = shape.getElementsByTagNameNS(DI_NS, "BPMNLabel")
                    if (labels.length == 0) return null
                    val label = labels.item(0) as org.w3c.dom.Element
                    val bounds = label.getElementsByTagNameNS(DC_NS, "Bounds")
                    if (bounds.length == 0) return null
                    val b = bounds.item(0) as org.w3c.dom.Element
                    return mapOf(
                        "x" to b.getAttribute("x").toDouble(),
                        "y" to b.getAttribute("y").toDouble(),
                        "width" to b.getAttribute("width").toDouble(),
                        "height" to b.getAttribute("height").toDouble(),
                    )
                }
            }
            return null
        }

        private fun serialize(model: BpmnModelInstance): String {
            val out = java.io.ByteArrayOutputStream()
            Bpmn.writeModelToStream(out, model)
            return out.toString(Charsets.UTF_8)
        }

        /** Creates a root ELK graph with a single node at (x, y) with given dimensions. */
        private fun rootWithNode(id: String, x: Double, y: Double, w: Double, h: Double): Pair<ElkNode, ElkNode> {
            val root = ElkGraphUtil.createGraph()
            val node = ElkGraphUtil.createNode(root)
            node.identifier = id
            node.x = x
            node.y = y
            node.width = w
            node.height = h
            return root to node
        }

        /** Minimal PlacedLayout with one shape and no labels/edges. */
        private fun singleShape(id: String, x: Double, y: Double, w: Double, h: Double): PlacedLayout = PlacedLayout(
            shapes = mapOf(id to Rect(x, y, w, h)),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = emptySet(),
        )
    }

    // ── absolutePosition (shared helper, lives in BpmnPlacementPass) ──────────

    @Test
    fun `absolutePosition of top-level node is its own coordinates`() {
        val (_, node) = rootWithNode("N", 100.0, 50.0, 100.0, 80.0)
        val (ax, ay) = BpmnPlacementPass.absolutePosition(node)
        assertEquals(100.0, ax, "top-level node absolute x")
        assertEquals(50.0, ay, "top-level node absolute y")
    }

    @Test
    fun `absolutePosition accumulates single parent offset`() {
        val root = ElkGraphUtil.createGraph()
        val parent = ElkGraphUtil.createNode(root)
        parent.identifier = "parent"
        parent.x = 100.0
        parent.y = 50.0
        parent.width = 300.0
        parent.height = 200.0

        val child = ElkGraphUtil.createNode(parent)
        child.identifier = "child"
        child.x = 10.0
        child.y = 20.0
        child.width = 100.0
        child.height = 80.0

        val (ax, ay) = BpmnPlacementPass.absolutePosition(child)
        assertEquals(110.0, ax, "child absolute x = parent.x + child.x")
        assertEquals(70.0, ay, "child absolute y = parent.y + child.y")
    }

    @Test
    fun `absolutePosition accumulates two-level parent offsets`() {
        val root = ElkGraphUtil.createGraph()
        val grandparent = ElkGraphUtil.createNode(root)
        grandparent.identifier = "gp"
        grandparent.x = 100.0
        grandparent.y = 50.0

        val parent = ElkGraphUtil.createNode(grandparent)
        parent.identifier = "p"
        parent.x = 20.0
        parent.y = 20.0

        val child = ElkGraphUtil.createNode(parent)
        child.identifier = "c"
        child.x = 10.0
        child.y = 10.0
        child.width = 50.0
        child.height = 40.0

        val (ax, ay) = BpmnPlacementPass.absolutePosition(child)
        assertEquals(130.0, ax, "grandchild absolute x = 100+20+10")
        assertEquals(80.0, ay, "grandchild absolute y = 50+20+10")
    }

    // ── DI write-back: shape bounds from PlacedLayout ─────────────────────────

    @Test
    fun `shape bounds are written verbatim from PlacedLayout`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S1"/>""",
        )
        val layout = singleShape("S1", 42.0, 55.0, 36.0, 36.0)
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)
        val b = boundsOf(xml, "S1")
        assertEquals(42.0, b["x"]!!, "x")
        assertEquals(55.0, b["y"]!!, "y")
        assertEquals(36.0, b["width"]!!, "width")
        assertEquals(36.0, b["height"]!!, "height")
    }

    @Test
    fun `subprocess BPMNShape has isExpanded true`() {
        val model = subprocessModel()
        val layout = PlacedLayout(
            shapes = mapOf(
                "Sub_1" to Rect(0.0, 0.0, 200.0, 150.0),
                "Child_1" to Rect(10.0, 10.0, 36.0, 36.0),
            ),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = setOf("Sub_1"),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)
        XmlAssert.assertThat(xml).withNamespaceContext(
            mapOf("bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI"),
        ).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Sub_1' and @isExpanded='true']").exist()
    }

    @Test
    fun `subprocess BPMNShape remains collapsed when absent from expanded layout IDs`() {
        val model = subprocessModel()
        val layout = PlacedLayout(
            shapes = mapOf(
                "Sub_1" to Rect(0.0, 0.0, 200.0, 150.0),
                "Child_1" to Rect(10.0, 10.0, 36.0, 36.0),
            ),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = emptySet(),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)
        XmlAssert.assertThat(xml).withNamespaceContext(
            mapOf("bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI"),
        ).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Sub_1' and @isExpanded='true']").doNotExist()
    }

    // ── DI write-back: label bounds from PlacedLayout (not element coords) ────

    @Test
    fun `label bounds come from PlacedLayout not element own coordinates`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S1" name="Start"/>""",
        )
        // Shape at (100, 50); label deliberately placed BELOW the shape
        val layout = PlacedLayout(
            shapes = mapOf("S1" to Rect(100.0, 50.0, 36.0, 36.0)),
            labels = mapOf("S1" to Rect(55.0, 90.0, 90.0, 20.0)),
            edges = emptyMap(),
            expanded = emptySet(),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)

        val labelBounds = labelBoundsOf(xml, "S1")
        assertNotNull(labelBounds, "Label bounds should be present for named element")
        // Label must be at (55,90), not at the shape's own (100,50)
        assertEquals(55.0, labelBounds["x"]!!, "label x from PlacedLayout (not element x)")
        assertEquals(90.0, labelBounds["y"]!!, "label y from PlacedLayout (not element y)")
        assertEquals(90.0, labelBounds["width"]!!, "label width from PlacedLayout")
        assertEquals(20.0, labelBounds["height"]!!, "label height from PlacedLayout")
    }

    @Test
    fun `element without label in PlacedLayout gets no BPMNLabel child`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S1" name="Start"/>""",
        )
        // No label entry in PlacedLayout
        val layout = singleShape("S1", 100.0, 50.0, 36.0, 36.0)
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)

        val labelBounds = labelBoundsOf(xml, "S1")
        assertTrue(labelBounds == null, "Element without label in PlacedLayout must not have BPMNLabel")
    }

    // ── DI write-back: boundary event shape from PlacedLayout ─────────────────

    @Test
    fun `boundary event shape uses PlacedLayout bounds directly`() {
        val model = boundaryModel()
        // Phase 2 already computed the boundary position — writer just serialises it
        val layout = PlacedLayout(
            shapes = mapOf(
                "Task_1" to Rect(200.0, 100.0, 100.0, 80.0),
                "Boundary_1" to Rect(232.0, 162.0, 36.0, 36.0), // placed on host bottom
            ),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = emptySet(),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)

        val beBounds = boundsOf(xml, "Boundary_1")
        assertEquals(232.0, beBounds["x"]!!, "Boundary_1 x from PlacedLayout")
        assertEquals(162.0, beBounds["y"]!!, "Boundary_1 y from PlacedLayout (host bottom)")
    }

    // ── DI write-back: edge waypoints from PlacedLayout ─────────────────────

    @Test
    fun `sequence edge waypoints are written verbatim from PlacedLayout`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S"><bpmn:outgoing>F</bpmn:outgoing></bpmn:startEvent>
               <bpmn:endEvent id="E"><bpmn:incoming>F</bpmn:incoming></bpmn:endEvent>
               <bpmn:sequenceFlow id="F" sourceRef="S" targetRef="E"/>""",
        )
        val layout = PlacedLayout(
            shapes = mapOf(
                "S" to Rect(0.0, 0.0, 36.0, 36.0),
                "E" to Rect(200.0, 0.0, 36.0, 36.0),
            ),
            labels = emptyMap(),
            edges = mapOf("F" to listOf(Point(36.0, 18.0), Point(100.0, 18.0), Point(200.0, 18.0))),
            expanded = emptySet(),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)

        val doc = parseDoc(xml)
        val edges = doc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
        var found = false
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as org.w3c.dom.Element
            if (edge.getAttribute("bpmnElement") == "F") {
                found = true
                val wps = edge.getElementsByTagNameNS("http://www.omg.org/spec/DD/20100524/DI", "waypoint")
                assertEquals(3, wps.length, "Expected 3 waypoints")
                val wp0 = wps.item(0) as org.w3c.dom.Element
                assertEquals(36.0, wp0.getAttribute("x").toDouble(), "wp0.x")
                assertEquals(18.0, wp0.getAttribute("y").toDouble(), "wp0.y")
            }
        }
        assertTrue(found, "BPMNEdge for F must exist")
    }

    @Test
    fun `missing sequence edge waypoints throws BpmnAutoLayoutException`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S"><bpmn:outgoing>F</bpmn:outgoing></bpmn:startEvent>
               <bpmn:endEvent id="E"><bpmn:incoming>F</bpmn:incoming></bpmn:endEvent>
               <bpmn:sequenceFlow id="F" sourceRef="S" targetRef="E"/>""",
        )
        // No edge waypoints for F in PlacedLayout
        val layout = PlacedLayout(
            shapes = mapOf(
                "S" to Rect(0.0, 0.0, 36.0, 36.0),
                "E" to Rect(200.0, 0.0, 36.0, 36.0),
            ),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = emptySet(),
        )
        kotlin.test.assertFailsWith<dev.groknull.bpmner.layout.BpmnAutoLayoutException>(
            message = "Should throw when sequence flow has no waypoints in PlacedLayout",
        ) {
            ElkToBpmnDiWriter.write(model, layout)
        }
    }

    // ── Plane bpmnElement references top-level Process ────────────────────────

    @Test
    fun `plane bpmnElement references the top-level Process not a subprocess`() {
        val model = subprocessModel()
        val layout = PlacedLayout(
            shapes = mapOf(
                "Sub_1" to Rect(0.0, 0.0, 200.0, 150.0),
                "Child_1" to Rect(10.0, 10.0, 36.0, 36.0),
            ),
            labels = emptyMap(),
            edges = emptyMap(),
            expanded = setOf("Sub_1"),
        )
        ElkToBpmnDiWriter.write(model, layout)
        val xml = serialize(model)

        XmlAssert.assertThat(xml).withNamespaceContext(
            mapOf("bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI"),
        ).nodesByXPath("//bpmndi:BPMNPlane[@bpmnElement='Process_1']").exist()
    }
}
