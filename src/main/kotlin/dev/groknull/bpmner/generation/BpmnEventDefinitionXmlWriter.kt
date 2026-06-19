/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.domain.BpmnErrorEventDefinition
import dev.groknull.bpmner.domain.BpmnEscalationEventDefinition
import dev.groknull.bpmner.domain.BpmnEventDefinition
import dev.groknull.bpmner.domain.BpmnMessageEventDefinition
import dev.groknull.bpmner.domain.BpmnNoneEventDefinition
import dev.groknull.bpmner.domain.BpmnSignalEventDefinition
import dev.groknull.bpmner.domain.BpmnTerminateEventDefinition
import dev.groknull.bpmner.domain.BpmnTimerEventDefinition
import dev.groknull.bpmner.domain.BpmnUnrecognizedEventDefinition
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
        is BpmnUnrecognizedEventDefinition -> error(
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
