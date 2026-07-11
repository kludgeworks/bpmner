/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class BpmnLayoutXmlProjector {

    private val dbFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }

    fun projectForLayout(originalXml: String): String {
        return serializeXml(parseXml(originalXml))
    }

    fun mergeLayout(originalXml: String, layoutedProjectedXml: String): String {
        val origDoc = parseXml(originalXml)
        val layoutDoc = parseXml(layoutedProjectedXml)

        val definitions = origDoc.documentElement
        require(definitions != null && definitions.localName == "definitions") {
            "Original XML missing <definitions> root"
        }

        // 1. Find extracted BPMNDiagram
        val layoutDiagrams = layoutDoc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNDiagram")
        require(layoutDiagrams.length > 0) {
            "Layouted XML missing BPMNDiagram"
        }
        val layoutDiagram = layoutDiagrams.item(0)

        // 2. Remove existing BPMNDiagram from origDoc if any
        val existingDiagrams = origDoc.getElementsByTagNameNS("http://www.omg.org/spec/BPMN/20100524/DI", "BPMNDiagram")
        val diagramsToRemove = mutableListOf<Node>()
        for (i in 0 until existingDiagrams.length) {
            diagramsToRemove.add(existingDiagrams.item(i))
        }
        diagramsToRemove.forEach { node ->
            node.parentNode?.removeChild(node)
        }

        // 3. Append the layout diagram
        val importedDiagram = origDoc.importNode(layoutDiagram, true)
        definitions.appendChild(importedDiagram)

        // 4. Ensure namespaces are registered using DOM API
        val xmlnsUri = "http://www.w3.org/2000/xmlns/"

        // Ensure bpmndi
        definitions.setAttributeNS(xmlnsUri, "xmlns:bpmndi", "http://www.omg.org/spec/BPMN/20100524/DI")
        // Ensure dc
        definitions.setAttributeNS(xmlnsUri, "xmlns:dc", "http://www.omg.org/spec/DD/20100524/DC")
        // Ensure di
        definitions.setAttributeNS(xmlnsUri, "xmlns:di", "http://www.omg.org/spec/DD/20100524/DI")

        return serializeXml(origDoc)
    }

    private fun parseXml(xml: String): Document {
        val builder = dbFactory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(xml)))
    }

    private fun serializeXml(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
