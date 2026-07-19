/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Stateless DOM/geometry helpers for inspecting generated BPMN-DI in layout tests.
 *
 * Extracted from the layout test suites so those classes stay focused on assertions rather
 * than XML plumbing.
 */
internal object LayoutDiInspector {

    private const val DI_NS = "http://www.omg.org/spec/BPMN/20100524/DI"
    private const val DC_NS = "http://www.omg.org/spec/DD/20100524/DC"
    private const val DD_DI_NS = "http://www.omg.org/spec/DD/20100524/DI"

    fun parse(xml: String): Document = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    /** Returns the DC bounds (x/y/width/height) of the BPMNShape for [bpmnElementId]. */
    fun shapeBounds(doc: Document, bpmnElementId: String): Map<String, Double> {
        val shapes = doc.getElementsByTagNameNS(DI_NS, "BPMNShape")
        for (i in 0 until shapes.length) {
            val shape = shapes.item(i) as Element
            if (shape.getAttribute("bpmnElement") == bpmnElementId) {
                val bounds = shape.getElementsByTagNameNS(DC_NS, "Bounds").item(0) as Element
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

    /** Returns the ordered waypoints of the BPMNEdge for [edgeId]. */
    fun edgeWaypoints(doc: Document, edgeId: String): List<Pair<Double, Double>> {
        val edges = doc.getElementsByTagNameNS(DI_NS, "BPMNEdge")
        for (i in 0 until edges.length) {
            val edge = edges.item(i) as Element
            if (edge.getAttribute("bpmnElement") != edgeId) continue
            val wps = edge.getElementsByTagNameNS(DD_DI_NS, "waypoint")
            return (0 until wps.length).map {
                val wp = wps.item(it) as Element
                wp.getAttribute("x").toDouble() to wp.getAttribute("y").toDouble()
            }
        }
        error("Edge $edgeId not found")
    }

    /** Loads a corpus input fixture from test resources. */
    fun loadCorpus(loader: ClassLoader, filename: String): String = loader.getResourceAsStream("layout-fixtures/$filename")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Corpus fixture not found: layout-fixtures/$filename")
}
