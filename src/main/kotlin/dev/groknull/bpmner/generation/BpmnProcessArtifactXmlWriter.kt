/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.domain.BpmnDefinition
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnProcessArtifactXmlWriter {
    fun write(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        writeLanes(document, process, definition)
        definition.annotations.forEach { annotation ->
            process.appendChild(
                document.bpmnElement("textAnnotation").also { el ->
                    el.setAttribute("id", annotation.id)
                    el.appendChild(document.bpmnElement("text").also { it.textContent = annotation.text })
                },
            )
        }
        definition.groups.forEach { group ->
            process.appendChild(
                document.bpmnElement("group").also { el ->
                    el.setAttribute("id", group.id)
                    if (!group.name.isNullOrBlank()) {
                        el.setAttribute("categoryValueRef", group.categoryValueId())
                    }
                },
            )
        }
        definition.associations.forEach { association ->
            process.appendChild(
                document.bpmnElement("association").also { el ->
                    el.setAttribute("id", association.id)
                    el.setAttribute("sourceRef", association.sourceRef)
                    el.setAttribute("targetRef", association.targetRef)
                },
            )
        }
        definition.dataObjects.forEach { dataObject ->
            process.appendChild(
                document.bpmnElement("dataObject").also { el ->
                    el.setAttribute("id", dataObject.id)
                    el.setAttribute("name", dataObject.name)
                },
            )
        }
        definition.dataStores.forEach { dataStore ->
            root.appendChild(
                document.bpmnElement("dataStore").also { el ->
                    el.setAttribute("id", dataStore.id)
                    el.setAttribute("name", dataStore.name)
                },
            )
        }
        writeCollaboration(document, root, process, definition)
    }

    private fun writeLanes(
        document: Document,
        process: Element,
        definition: BpmnDefinition,
    ) {
        if (definition.lanes.isEmpty()) return

        val laneSet = document.bpmnElement("laneSet").also { laneSet ->
            laneSet.setAttribute("id", "LaneSet_${definition.processId}")
            definition.lanes.forEach { lane ->
                laneSet.appendChild(
                    document.bpmnElement("lane").also { element ->
                        element.setAttribute("id", lane.id)
                        lane.name?.takeIf { it.isNotBlank() }?.let { element.setAttribute("name", it) }
                        lane.flowNodeRefs.forEach { ref ->
                            element.appendChild(document.bpmnElement("flowNodeRef").also { it.textContent = ref })
                        }
                    },
                )
            }
        }
        process.insertBefore(laneSet, process.firstChild)
    }

    private fun writeCollaboration(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        if (definition.participants.isEmpty()) return

        val collaboration = document.bpmnElement("collaboration").also { collaboration ->
            collaboration.setAttribute("id", "Collaboration_${definition.processId}")
            definition.participants.forEach { participant ->
                collaboration.appendChild(
                    document.bpmnElement("participant").also { element ->
                        element.setAttribute("id", participant.id)
                        participant.name?.takeIf { it.isNotBlank() }?.let { element.setAttribute("name", it) }
                        participant.processRef?.takeIf { it.isNotBlank() }?.let { element.setAttribute("processRef", it) }
                    },
                )
            }
            definition.messageFlows.forEach { messageFlow ->
                collaboration.appendChild(
                    document.bpmnElement("messageFlow").also { element ->
                        element.setAttribute("id", messageFlow.id)
                        messageFlow.name?.takeIf { it.isNotBlank() }?.let { element.setAttribute("name", it) }
                        element.setAttribute("sourceRef", messageFlow.sourceRef)
                        element.setAttribute("targetRef", messageFlow.targetRef)
                    },
                )
            }
        }
        root.insertBefore(collaboration, process)
    }
}
