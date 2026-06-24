/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnEventDefinitionXmlWriter {
    fun appendTo(
        eventElement: Element,
        document: Document,
        definition: BpmnEventDefinition,
    ) {
        if (definition is BpmnNoneEventDefinition) return
        eventElement.appendChild(toElement(document, definition))
    }

    private fun toElement(
        document: Document,
        definition: BpmnEventDefinition,
    ): Element = when (definition) {
        is BpmnTimerEventDefinition -> timerElement(document, definition)
        is BpmnMessageEventDefinition -> refElement(document, "messageEventDefinition", "messageRef", definition.messageRef)
        is BpmnSignalEventDefinition -> refElement(document, "signalEventDefinition", "signalRef", definition.signalRef)
        is BpmnErrorEventDefinition -> refElement(document, "errorEventDefinition", "errorRef", definition.errorRef)
        is BpmnEscalationEventDefinition -> refElement(
            document,
            "escalationEventDefinition",
            "escalationRef",
            definition.escalationRef,
        )
        is BpmnTerminateEventDefinition -> document.bpmnElement("terminateEventDefinition")
        is BpmnNoneEventDefinition -> error("none event definition must not render XML")
        is BpmnUnrecognizedEventDefinition ->
            throw RetryableBpmnGenerationException(
                "BpmnUnrecognizedEventDefinition (${definition.typeName}) cannot be rendered to XML. " +
                    "The generator must filter unrecognized event definitions before reaching this point.",
            )
    }

    private fun timerElement(
        document: Document,
        definition: BpmnTimerEventDefinition,
    ): Element = document.bpmnElement("timerEventDefinition").also { event ->
        event.appendChild(
            document.bpmnElement(definition.timerKind.childElementName()).also {
                it.textContent = definition.expression
            },
        )
    }

    private fun refElement(
        document: Document,
        localName: String,
        attributeName: String,
        ref: String,
    ): Element = document.bpmnElement(localName).also {
        it.setAttribute(attributeName, ref)
    }

    private fun BpmnTimerKind.childElementName(): String = when (this) {
        BpmnTimerKind.DATE -> "timeDate"
        BpmnTimerKind.DURATION -> "timeDuration"
        BpmnTimerKind.CYCLE -> "timeCycle"
    }
}
