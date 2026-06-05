/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal const val BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"
internal const val BPMNER_EXT_NS = "https://groknull.dev/bpmner/ext"

private const val DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl"
private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"

internal fun parseBpmnDocument(xml: String): Document = DocumentBuilderFactory
    .newInstance()
    .also {
        it.isNamespaceAware = true
        it.setFeature(DISALLOW_DOCTYPE_DECL, true)
        it.setFeature(EXTERNAL_GENERAL_ENTITIES, false)
        it.setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
        it.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        it.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        it.isXIncludeAware = false
        it.isExpandEntityReferences = false
    }.newDocumentBuilder()
    .parse(org.xml.sax.InputSource(StringReader(xml)))

internal fun writeBpmnDocument(document: Document): String {
    val writer = StringWriter()
    TransformerFactory
        .newInstance()
        .also {
            it.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            it.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }.newTransformer()
        .also {
            it.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            it.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(writer))
    return writer.toString()
}

internal fun Document.bpmnElement(localName: String): Element = createElementNS(BPMN_NS, "bpmn:$localName")

internal fun Document.bpmnElements(localName: String): Sequence<Element> = getElementsByTagNameNS(BPMN_NS, localName).elements()

internal fun NodeList.elements(): Sequence<Element> = sequence {
    for (index in 0 until length) {
        (item(index) as? Element)?.let { yield(it) }
    }
}

internal fun dev.groknull.bpmner.api.BpmnGroup.categoryId(): String = "Category_$id"

internal fun dev.groknull.bpmner.api.BpmnGroup.categoryValueId(): String = "CategoryValue_$id"
