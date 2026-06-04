/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnProcessArtifactXmlWriter {
    fun write(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
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
    }
}
