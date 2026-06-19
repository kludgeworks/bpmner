/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.domain.BpmnDefinition
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnCatalogXmlWriter {
    fun write(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        writeEscalations(document, root, process, definition)
        writeErrors(document, root, process, definition)
        writeSignals(document, root, process, definition)
        writeMessages(document, root, process, definition)
        writeGroupCategories(document, root, process, definition)
    }

    private fun writeEscalations(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.escalations.asReversed().forEach { escalation ->
            root.insertBefore(
                document.bpmnElement("escalation").also {
                    it.setAttribute("id", escalation.id)
                    it.setAttribute("escalationCode", escalation.code)
                    escalation.name?.takeIf { name -> name.isNotBlank() }?.let { name -> it.setAttribute("name", name) }
                },
                process,
            )
        }
    }

    private fun writeErrors(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.errors.asReversed().forEach { error ->
            root.insertBefore(
                document.bpmnElement("error").also {
                    it.setAttribute("id", error.id)
                    it.setAttribute("errorCode", error.code)
                    error.name?.takeIf { name -> name.isNotBlank() }?.let { name -> it.setAttribute("name", name) }
                },
                process,
            )
        }
    }

    private fun writeSignals(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.signals.asReversed().forEach { signal ->
            root.insertBefore(
                document.bpmnElement("signal").also {
                    it.setAttribute("id", signal.id)
                    it.setAttribute("name", signal.name)
                },
                process,
            )
        }
    }

    private fun writeMessages(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.messages.asReversed().forEach { message ->
            root.insertBefore(
                document.bpmnElement("message").also {
                    it.setAttribute("id", message.id)
                    it.setAttribute("name", message.name)
                },
                process,
            )
        }
    }

    private fun writeGroupCategories(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        definition.groups.asReversed().forEach { group ->
            group.name?.takeIf { it.isNotBlank() }?.let { name ->
                root.insertBefore(
                    document.bpmnElement("category").also { category ->
                        category.setAttribute("id", group.categoryId())
                        category.appendChild(
                            document.bpmnElement("categoryValue").also { value ->
                                value.setAttribute("id", group.categoryValueId())
                                value.setAttribute("value", name)
                            },
                        )
                    },
                    process,
                )
            }
        }
    }
}
