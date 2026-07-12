/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkGraphResult
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
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
import kotlin.test.assertFailsWith

/**
 * Layer 3: verifies [ElkToBpmnDiWriter] coordinate translation using manually constructed
 * ELK graphs with known geometry. Does NOT run the layout engine.
 *
 * Every test builds a minimal ELK node tree, manually sets x/y/width/height as ELK would
 * after layout, then calls [ElkToBpmnDiWriter.write] and asserts the emitted DI.
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

        private fun twoLevelSubprocessModel(): BpmnModelInstance {
            val xml = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:subProcess id="Outer">
      <bpmn:subProcess id="Inner">
        <bpmn:startEvent id="Grandchild"/>
      </bpmn:subProcess>
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
    }

    // ── absolutePosition ──────────────────────────────────────────────────────

    @Test
    fun `absolutePosition of top-level node is its own coordinates`() {
        val (_, node) = rootWithNode("N", 100.0, 50.0, 100.0, 80.0)
        val (ax, ay) = ElkToBpmnDiWriter.absolutePosition(node)
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

        val (ax, ay) = ElkToBpmnDiWriter.absolutePosition(child)
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

        val (ax, ay) = ElkToBpmnDiWriter.absolutePosition(child)
        assertEquals(130.0, ax, "grandchild absolute x = 100+20+10")
        assertEquals(80.0, ay, "grandchild absolute y = 50+20+10")
    }

    // ── DI write-back: subprocess shapes ─────────────────────────────────────

    @Test
    fun `child shape bounds use absolute position not relative ELK coordinates`() {
        val model = subprocessModel()
        val root = ElkGraphUtil.createGraph()

        val compound = ElkGraphUtil.createNode(root)
        compound.identifier = "Sub_1"
        compound.x = 100.0
        compound.y = 50.0
        compound.width = 300.0
        compound.height = 200.0

        val child = ElkGraphUtil.createNode(compound)
        child.identifier = "Child_1"
        child.x = 10.0
        child.y = 20.0
        child.width = 36.0
        child.height = 36.0

        val result = ElkGraphResult(root, mapOf("Sub_1" to compound, "Child_1" to child), emptyMap(), emptyMap())
        ElkToBpmnDiWriter.write(model, result)
        val xml = serialize(model)

        val childBounds = boundsOf(xml, "Child_1")
        assertEquals(110.0, childBounds["x"]!!, "Child_1 absolute x = 100+10")
        assertEquals(70.0, childBounds["y"]!!, "Child_1 absolute y = 50+20")
    }

    @Test
    fun `two-level nesting produces correct grandchild absolute position`() {
        val model = twoLevelSubprocessModel()
        val root = ElkGraphUtil.createGraph()

        val outer = ElkGraphUtil.createNode(root)
        outer.identifier = "Outer"
        outer.x = 100.0
        outer.y = 50.0
        outer.width = 400.0
        outer.height = 300.0

        val inner = ElkGraphUtil.createNode(outer)
        inner.identifier = "Inner"
        inner.x = 20.0
        inner.y = 20.0
        inner.width = 200.0
        inner.height = 160.0

        val grandchild = ElkGraphUtil.createNode(inner)
        grandchild.identifier = "Grandchild"
        grandchild.x = 10.0
        grandchild.y = 10.0
        grandchild.width = 36.0
        grandchild.height = 36.0

        val result = ElkGraphResult(
            root,
            mapOf("Outer" to outer, "Inner" to inner, "Grandchild" to grandchild),
            emptyMap(),
            emptyMap(),
        )
        ElkToBpmnDiWriter.write(model, result)
        val xml = serialize(model)

        val gcBounds = boundsOf(xml, "Grandchild")
        assertEquals(130.0, gcBounds["x"]!!, "Grandchild absolute x = 100+20+10")
        assertEquals(80.0, gcBounds["y"]!!, "Grandchild absolute y = 50+20+10")
    }

    @Test
    fun `subprocess BPMNShape has isExpanded true`() {
        val model = subprocessModel()
        val root = ElkGraphUtil.createGraph()

        val compound = ElkGraphUtil.createNode(root)
        compound.identifier = "Sub_1"
        compound.x = 100.0
        compound.y = 50.0
        compound.width = 300.0
        compound.height = 200.0

        val child = ElkGraphUtil.createNode(compound)
        child.identifier = "Child_1"
        child.x = 10.0
        child.y = 20.0
        child.width = 36.0
        child.height = 36.0

        val result = ElkGraphResult(root, mapOf("Sub_1" to compound, "Child_1" to child), emptyMap(), emptyMap())
        ElkToBpmnDiWriter.write(model, result)
        val xml = serialize(model)

        XmlAssert.assertThat(xml).withNamespaceContext(
            mapOf("bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI"),
        ).nodesByXPath("//bpmndi:BPMNShape[@bpmnElement='Sub_1' and @isExpanded='true']").exist()
    }

    // ── DI write-back: boundary event shapes ─────────────────────────────────

    @Test
    fun `boundary event shape is positioned at host perimeter via port offset`() {
        val model = boundaryModel()
        val root = ElkGraphUtil.createGraph()

        // Host node at absolute (200, 100)
        val hostNode = ElkGraphUtil.createNode(root)
        hostNode.identifier = "Task_1"
        hostNode.x = 200.0
        hostNode.y = 100.0
        hostNode.width = 100.0
        hostNode.height = 80.0

        // Port on host representing the boundary attachment point at (45, 70) relative to host
        val port = ElkGraphUtil.createPort(hostNode)
        port.identifier = "port_Boundary_1"
        port.x = 45.0
        port.y = 70.0
        port.width = 10.0
        port.height = 10.0
        port.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
        port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)

        // Boundary event sibling node (size EVENT_SIZE x EVENT_SIZE = 36x36)
        val beNode = ElkGraphUtil.createNode(root)
        beNode.identifier = "Boundary_1"
        beNode.x = 0.0
        beNode.y = 0.0
        beNode.width = 36.0
        beNode.height = 36.0

        val portMap = mapOf("Boundary_1" to port)
        val nodeMap = mapOf("Task_1" to hostNode, "Boundary_1" to beNode)
        val result = ElkGraphResult(root, nodeMap, portMap, emptyMap())
        ElkToBpmnDiWriter.write(model, result)
        val xml = serialize(model)

        // Boundary centre = host abs (200,100) + port offset (45+5, 70+5) - half event (18,18)
        // = (200+50-18, 100+75-18) = (232, 157)
        // → shape top-left = (232, 157)
        val beBounds = boundsOf(xml, "Boundary_1")
        assertEquals(232.0, beBounds["x"]!!, "Boundary_1 x = host.x + port.x + port.w/2 - beW/2")
        assertEquals(157.0, beBounds["y"]!!, "Boundary_1 y = host.y + port.y + port.h/2 - beH/2")
    }

    // ── (0,0) waypoint fallback replaced with exception ───────────────────────

    @Test
    fun `writeWaypoints throws BpmnAutoLayoutException when ELK edge has no section`() {
        val model = minimalModel(
            """<bpmn:startEvent id="S"><bpmn:outgoing>F</bpmn:outgoing></bpmn:startEvent>
               <bpmn:endEvent id="E"><bpmn:incoming>F</bpmn:incoming></bpmn:endEvent>
               <bpmn:sequenceFlow id="F" sourceRef="S" targetRef="E"/>""",
        )

        val root = ElkGraphUtil.createGraph()
        val source = ElkGraphUtil.createNode(root)
        source.identifier = "S"
        source.x = 0.0
        source.y = 0.0
        source.width = 36.0
        source.height = 36.0
        val target = ElkGraphUtil.createNode(root)
        target.identifier = "E"
        target.x = 200.0
        target.y = 0.0
        target.width = 36.0
        target.height = 36.0

        // Manually create an edge with NO sections (simulates ELK producing no routing)
        val sectionlessEdge = ElkGraphUtil.createEdge(root)
        sectionlessEdge.identifier = "F"
        sectionlessEdge.sources.add(source)
        sectionlessEdge.targets.add(target)
        // Do NOT add any sections — sections list remains empty

        val nodeMap = mapOf("S" to source, "E" to target)
        val edgeMap = mapOf("F" to sectionlessEdge)
        val result = ElkGraphResult(root, nodeMap, emptyMap(), edgeMap)

        assertFailsWith<BpmnAutoLayoutException>(
            message = "Should throw when ELK edge has no routing section",
        ) {
            ElkToBpmnDiWriter.write(model, result)
        }
    }

    // ── Plane bpmnElement references top-level Process ────────────────────────

    @Test
    fun `plane bpmnElement references the top-level Process not a subprocess`() {
        val model = subprocessModel()
        val root = ElkGraphUtil.createGraph()
        val compound = ElkGraphUtil.createNode(root)
        compound.identifier = "Sub_1"
        compound.x = 0.0
        compound.y = 0.0
        compound.width = 200.0
        compound.height = 150.0
        val child = ElkGraphUtil.createNode(compound)
        child.identifier = "Child_1"
        child.x = 10.0
        child.y = 10.0
        child.width = 36.0
        child.height = 36.0

        val result = ElkGraphResult(root, mapOf("Sub_1" to compound, "Child_1" to child), emptyMap(), emptyMap())
        ElkToBpmnDiWriter.write(model, result)
        val xml = serialize(model)

        XmlAssert.assertThat(xml).withNamespaceContext(
            mapOf("bpmndi" to "http://www.omg.org/spec/BPMN/20100524/DI"),
        ).nodesByXPath("//bpmndi:BPMNPlane[@bpmnElement='Process_1']").exist()
    }
}
