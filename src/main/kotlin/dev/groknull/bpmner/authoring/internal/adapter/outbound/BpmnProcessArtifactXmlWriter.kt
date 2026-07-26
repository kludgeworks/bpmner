/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnDefinition
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
        // dataObjectReference/dataStoreReference are flowElements, not artifacts (confirmed
        // against the camunda-bpm-model Java API: both extend FlowElement) — BPMN's tProcess
        // content model groups all flowElements before all artifacts, so these must land before
        // the textAnnotation/group/association loop below, not after it.
        writeDataElements(document, root, process, definition)
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
        writeDataAssociations(document, definition)
        writeCollaboration(document, root, process, definition)
    }

    /**
     * `dataObjectReference`/`dataStoreReference` (process-scoped flowElements) plus each one's
     * backing non-visual element: `dataObject` (a `FlowElement`, so it lives in the process
     * alongside its reference) and `dataStore` (a `RootElement`, so it lives directly under
     * `definitions`, per the camunda-bpm-model Java API's declared supertypes).
     */
    private fun writeDataElements(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.dataObjectReferences.forEach { ref ->
            val dataObjectId = "DataObject_${ref.id}"
            process.appendChild(document.bpmnElement("dataObject").also { it.setAttribute("id", dataObjectId) })
            process.appendChild(
                document.bpmnElement("dataObjectReference").also { el ->
                    el.setAttribute("id", ref.id)
                    if (!ref.name.isNullOrBlank()) el.setAttribute("name", ref.name)
                    el.setAttribute("dataObjectRef", dataObjectId)
                },
            )
        }
        definition.dataStoreReferences.forEach { ref ->
            val dataStoreId = "DataStore_${ref.id}"
            root.appendChild(
                document.bpmnElement("dataStore").also { el ->
                    el.setAttribute("id", dataStoreId)
                    if (!ref.name.isNullOrBlank()) el.setAttribute("name", ref.name)
                },
            )
            process.appendChild(
                document.bpmnElement("dataStoreReference").also { el ->
                    el.setAttribute("id", ref.id)
                    if (!ref.name.isNullOrBlank()) el.setAttribute("name", ref.name)
                    el.setAttribute("dataStoreRef", dataStoreId)
                },
            )
        }
    }

    /**
     * `dataInputAssociation`/`dataOutputAssociation` nest inside their owning activity element
     * (`tActivity`'s own content model — confirmed via the Java API's
     * `Activity.getDataInputAssociations()`/`getDataOutputAssociations()`), not the process, so
     * these are found by id and appended directly to that element rather than to `process`.
     * `sourceRef`/`targetRef` are child elements carrying the referenced id as text content (an
     * IDREF), not attributes — unlike `association`, which is a plain `BaseElement`-to-
     * `BaseElement` edge and uses attributes.
     *
     * `tDataAssociation` (confirmed against the bundled `Semantic.xsd`, not assumed) requires
     * exactly one `targetRef` (`minOccurs="1"`) regardless of direction; `sourceRef` is optional
     * (`minOccurs="0" maxOccurs="unbounded"`). The fully correct BPMN model routes a
     * `dataInputAssociation`'s `targetRef` to the activity's own `ioSpecification`/`dataInput`
     * id, which we don't model (no session has needed an activity's data *quantity/state*
     * constraints yet) — self-referencing the owning activity's own id satisfies the schema's
     * ID-reference syntax (XSD does not check the referenced element's semantic type) without
     * that extra machinery.
     */
    private fun writeDataAssociations(document: Document, definition: BpmnDefinition) {
        definition.dataInputAssociations.forEach { assoc ->
            val activity = document.bpmnElementById(assoc.activityRef) ?: return@forEach
            activity.appendChild(
                document.bpmnElement("dataInputAssociation").also { el ->
                    el.setAttribute("id", assoc.id)
                    el.appendChild(document.bpmnElement("sourceRef").also { it.textContent = assoc.sourceRef })
                    el.appendChild(document.bpmnElement("targetRef").also { it.textContent = assoc.activityRef })
                },
            )
        }
        definition.dataOutputAssociations.forEach { assoc ->
            val activity = document.bpmnElementById(assoc.activityRef) ?: return@forEach
            activity.appendChild(
                document.bpmnElement("dataOutputAssociation").also { el ->
                    el.setAttribute("id", assoc.id)
                    el.appendChild(document.bpmnElement("targetRef").also { it.textContent = assoc.targetRef })
                },
            )
        }
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
