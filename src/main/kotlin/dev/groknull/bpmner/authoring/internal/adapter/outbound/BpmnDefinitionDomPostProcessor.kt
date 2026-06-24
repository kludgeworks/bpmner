/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnDefinitionDomPostProcessor(
    private val catalogWriter: BpmnCatalogXmlWriter = BpmnCatalogXmlWriter(),
    private val nodePayloadWriter: BpmnNodePayloadXmlWriter = BpmnNodePayloadXmlWriter(),
    private val processArtifactWriter: BpmnProcessArtifactXmlWriter = BpmnProcessArtifactXmlWriter(),
) {
    fun postProcess(xml: String, definition: BpmnDefinition): String {
        val document = parseBpmnDocument(xml)
        val root = document.documentElement
        val process = document.processElement()

        catalogWriter.write(document, root, process, definition)
        val usesBpmnerNamespace = nodePayloadWriter.write(document, definition)
        processArtifactWriter.write(document, root, process, definition)
        if (usesBpmnerNamespace) {
            root.declareBpmnerNamespace()
        }

        return writeBpmnDocument(document)
    }

    private fun Document.processElement(): Element = bpmnElements("process").firstOrNull()
        ?: throw RetryableBpmnGenerationException("Unable to locate process in generated BPMN XML")

    private fun Element.declareBpmnerNamespace() {
        setAttributeNS(
            "http://www.w3.org/2000/xmlns/",
            "xmlns:bpmner",
            BPMNER_EXT_NS,
        )
    }
}
