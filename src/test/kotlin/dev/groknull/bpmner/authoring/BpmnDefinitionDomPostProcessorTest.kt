/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

// Site 11: BpmnDefinitionDomPostProcessor.processElement() throws
// RetryableBpmnGenerationException when the BPMN XML contains no <process> element.
class BpmnDefinitionDomPostProcessorTest {
    private val postProcessor = BpmnDefinitionDomPostProcessor()

    // A minimal BpmnDefinition is required by postProcess but the XML path under test
    // throws before reading the definition contents.
    private val anyDefinition = BpmnDefinition(
        processId = "p1",
        processName = "Test",
        nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")),
        sequences = listOf(BpmnEdge("f", "s", "e")),
    )

    @Test
    fun `postProcess with no process element throws RetryableBpmnGenerationException`() {
        // BPMN definitions XML that contains no <process> element — the post-processor
        // must surface this as a retryable failure so the repair loop can re-generate.
        val noProcessXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         id="Definitions_1"
                         targetNamespace="http://bpmn.io/schema/bpmn">
            </definitions>
            """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            postProcessor.postProcess(noProcessXml, anyDefinition)
        }
        assertContains(ex.message!!, "Unable to locate process in generated BPMN XML")
    }
}
