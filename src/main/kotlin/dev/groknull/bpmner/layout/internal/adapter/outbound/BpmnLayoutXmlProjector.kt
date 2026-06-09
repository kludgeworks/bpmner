/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.w3c.dom.Document
import org.w3c.dom.Element
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

    private val elementsToStrip = setOf(
        "dataObject",
        "dataObjectReference",
        "dataStore",
        "dataStoreReference",
        "dataInputAssociation",
        "dataOutputAssociation",
    )

    fun projectForLayout(originalXml: String): String {
        val doc = parseXml(originalXml)

        val removedIds = mutableSetOf<String>()
        val nodesToRemove = mutableListOf<Node>()

        // 1. Find all elements to strip
        val allElements = doc.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val node = allElements.item(i) as? Element ?: continue
            val isBpmnModel = node.namespaceURI == "http://www.omg.org/spec/BPMN/20100524/MODEL"
            if (isBpmnModel && elementsToStrip.contains(node.localName)) {
                nodesToRemove.add(node)
                val id = node.getAttribute("id")
                if (id.isNotEmpty()) {
                    removedIds.add(id)
                }
            }
        }

        // 2. Find associations that point to stripped elements
        nodesToRemove.addAll(findAssociationsToRemove(allElements, removedIds))

        // 3. Remove them from DOM
        nodesToRemove.forEach { node ->
            node.parentNode?.removeChild(node)
        }

        return serializeXml(doc)
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

    private fun findAssociationsToRemove(allElements: org.w3c.dom.NodeList, removedIds: Set<String>): List<Node> {
        val nodesToRemove = mutableListOf<Node>()
        for (i in 0 until allElements.length) {
            val node = allElements.item(i) as? Element ?: continue
            val isAssoc = node.namespaceURI == "http://www.omg.org/spec/BPMN/20100524/MODEL" &&
                node.localName == "association"
            if (isAssoc) {
                val sourceRef = node.getAttribute("sourceRef")
                val targetRef = node.getAttribute("targetRef")
                if (removedIds.contains(sourceRef) || removedIds.contains(targetRef)) {
                    nodesToRemove.add(node)
                }
            }
        }
        return nodesToRemove
    }
}
