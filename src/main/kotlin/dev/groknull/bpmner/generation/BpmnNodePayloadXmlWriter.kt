/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTask
import dev.groknull.bpmner.api.DataFlowDirection
import dev.groknull.bpmner.api.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.api.StandardLoopCharacteristics
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDataAssociation
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnStartEvent
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnNodePayloadXmlWriter(
    private val eventDefinitionWriter: BpmnEventDefinitionXmlWriter = BpmnEventDefinitionXmlWriter(),
) {
    fun write(
        document: Document,
        definition: BpmnDefinition,
    ): Boolean {
        val taskElementsById = document.payloadTaskElementsById()
        val eventElementsById = document.eventElementsById()
        var bpmnerNamespaceUsed = false

        definition.nodes.forEach { node ->
            if (writeNodePayload(document, node, taskElementsById, eventElementsById)) {
                bpmnerNamespaceUsed = true
            }
        }

        val allTaskElementsById = document.allTaskElementsById()
        writeDataAssociations(document, allTaskElementsById, definition.dataAssociations)
        if (writeLoopCharacteristics(document, allTaskElementsById, definition.nodes.filterIsInstance<BpmnTask>())) {
            bpmnerNamespaceUsed = true
        }
        return bpmnerNamespaceUsed
    }

    private fun writeNodePayload(
        document: Document,
        node: BpmnNode,
        taskElementsById: Map<String, Element>,
        eventElementsById: Map<String, Element>,
    ): Boolean = when (node) {
        is BpmnStartEvent -> {
            val element = eventElementsById.eventElement(node.id)
            if (!node.isInterrupting) {
                element.setAttribute("isInterrupting", node.isInterrupting.toString())
            }
            eventDefinitionWriter.appendTo(element, document, node.eventDefinition)
            false
        }
        is BpmnIntermediateCatchEvent -> {
            eventDefinitionWriter.appendTo(eventElementsById.eventElement(node.id), document, node.eventDefinition)
            false
        }
        is BpmnIntermediateThrowEvent -> {
            eventDefinitionWriter.appendTo(eventElementsById.eventElement(node.id), document, node.eventDefinition)
            false
        }
        is BpmnBoundaryEvent -> {
            val element = eventElementsById.eventElement(node.id)
            element.setAttribute("attachedToRef", node.attachedToRef)
            element.setAttribute("cancelActivity", node.cancelActivity.toString())
            eventDefinitionWriter.appendTo(element, document, node.eventDefinition)
            false
        }
        is BpmnEndEvent -> {
            eventDefinitionWriter.appendTo(eventElementsById.eventElement(node.id), document, node.eventDefinition)
            false
        }
        is BpmnSendTask -> {
            taskElementsById.taskElement(node.id).setAttribute("messageRef", node.messageRef)
            false
        }
        is BpmnReceiveTask -> {
            taskElementsById.taskElement(node.id).setAttribute("messageRef", node.messageRef)
            false
        }
        is BpmnBusinessRuleTask -> {
            taskElementsById.taskElement(node.id).setAttributeNS(
                BPMNER_EXT_NS,
                "bpmner:decisionRef",
                node.decisionRef,
            )
            true
        }
        else -> false
    }

    private fun writeDataAssociations(
        document: Document,
        allTaskElementsById: Map<String, Element>,
        dataAssociations: List<BpmnDataAssociation>,
    ) {
        dataAssociations.forEach { da ->
            val element = allTaskElementsById[da.sourceRef]
                ?: error("Data association '${da.id}' sourceRef '${da.sourceRef}' has no rendered task element")
            element.appendDataAssociation(document, da)
        }
    }

    private fun writeLoopCharacteristics(
        document: Document,
        allTaskElementsById: Map<String, Element>,
        tasks: List<BpmnTask>,
    ): Boolean {
        var bpmnerNamespaceUsed = false
        tasks.forEach { task ->
            task.multiInstance?.let { mi ->
                val element = allTaskElementsById[task.id]
                    ?: error("Task '${task.id}' has a multi-instance marker but no task element was rendered for it")
                element.appendMultiInstance(document, mi)
                bpmnerNamespaceUsed = true
            }
            task.standardLoop?.let { loop ->
                val element = allTaskElementsById[task.id]
                    ?: error("Task '${task.id}' has a standard-loop marker but no task element was rendered for it")
                element.appendStandardLoop(document, loop)
            }
        }
        return bpmnerNamespaceUsed
    }

    private fun Document.payloadTaskElementsById(): Map<String, Element> = listOf(
        "sendTask",
        "receiveTask",
        "businessRuleTask",
    ).elementsById(this)

    private fun Document.allTaskElementsById(): Map<String, Element> = BPMN_TASK_LOCAL_NAMES.elementsById(this)

    private fun List<String>.elementsById(document: Document): Map<String, Element> {
        return flatMap { document.bpmnElements(it).toList() }
            .associateBy { it.getAttribute("id") }
            .filterKeys { it.isNotBlank() }
    }

    private fun Document.eventElementsById(): Map<String, Element> = listOf(
        "startEvent",
        "intermediateCatchEvent",
        "intermediateThrowEvent",
        "boundaryEvent",
        "endEvent",
    ).elementsById(this)

    private fun Map<String, Element>.taskElement(id: String): Element {
        return this[id] ?: error("Task element with id='$id' not found in rendered XML")
    }

    private fun Map<String, Element>.eventElement(id: String): Element {
        return this[id] ?: error("Unable to locate BPMN event element id=\"$id\" in generated BPMN XML")
    }
}

private fun Element.appendMultiInstance(
    document: Document,
    mi: MultiInstanceLoopCharacteristics,
) {
    appendChild(
        document.bpmnElement("multiInstanceLoopCharacteristics").also { loop ->
            loop.setAttribute("isSequential", (mi.mode == MultiInstanceMode.SEQUENTIAL).toString())
            loop.setAttributeNS(
                BPMNER_EXT_NS,
                "bpmner:collectionDescription",
                mi.collectionDescription,
            )
            mi.loopCardinality?.let { cardinality ->
                loop.appendChild(
                    document.bpmnElement("loopCardinality").also { it.textContent = cardinality.toString() },
                )
            }
            mi.completionCondition?.takeIf { it.isNotBlank() }?.let { condition ->
                loop.appendChild(
                    document.bpmnElement("completionCondition").also { it.textContent = condition },
                )
            }
        },
    )
}

private fun Element.appendStandardLoop(
    document: Document,
    loop: StandardLoopCharacteristics,
) {
    appendChild(
        document.bpmnElement("standardLoopCharacteristics").also { el ->
            el.setAttribute("testBefore", loop.testBefore.toString())
            loop.loopMaximum?.let { el.setAttribute("loopMaximum", it.toString()) }
            loop.loopCondition?.takeIf { it.isNotBlank() }?.let { condition ->
                el.appendChild(
                    document.bpmnElement("loopCondition").also { it.textContent = condition },
                )
            }
        },
    )
}

private fun Element.appendDataAssociation(document: Document, da: BpmnDataAssociation) {
    val (localName, sourceId, targetId) = when (da.direction) {
        DataFlowDirection.READ -> Triple("dataInputAssociation", da.targetRef, da.sourceRef)
        DataFlowDirection.WRITE -> Triple("dataOutputAssociation", da.sourceRef, da.targetRef)
    }
    appendChild(
        document.bpmnElement(localName).also { assoc ->
            assoc.setAttribute("id", da.id)
            assoc.appendChild(document.bpmnElement("sourceRef").also { it.textContent = sourceId })
            assoc.appendChild(document.bpmnElement("targetRef").also { it.textContent = targetId })
        },
    )
}
