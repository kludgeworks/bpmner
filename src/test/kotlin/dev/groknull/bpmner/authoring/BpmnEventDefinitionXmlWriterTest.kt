/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import org.w3c.dom.Document
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

// Site 13: BpmnEventDefinitionXmlWriter throws RetryableBpmnGenerationException when
// asked to render a BpmnUnrecognizedEventDefinition — the generator must filter these
// before they reach the XML writer.
class BpmnEventDefinitionXmlWriterTest {
    private val writer = BpmnEventDefinitionXmlWriter()

    private fun emptyDocument(): Document = DocumentBuilderFactory
        .newInstance()
        .also { it.isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(
            org.xml.sax.InputSource(
                StringReader(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"/>
                    """.trimIndent(),
                ),
            ),
        )

    @Test
    fun `appendTo with BpmnUnrecognizedEventDefinition throws RetryableBpmnGenerationException`() {
        val document = emptyDocument()
        val eventElement = document.documentElement
        val unrecognized = BpmnUnrecognizedEventDefinition(typeName = "bpmn:customEventDefinition")

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.appendTo(eventElement, document, unrecognized)
        }
        assertContains(ex.message!!, "BpmnUnrecognizedEventDefinition")
        assertContains(ex.message!!, "bpmn:customEventDefinition")
    }
}
