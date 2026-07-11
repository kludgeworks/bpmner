/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnDefinition
import org.w3c.dom.Document
import org.w3c.dom.Element

internal class BpmnCatalogXmlWriter {
    fun write(
        document: Document,
        root: Element,
        process: Element,
        definition: BpmnDefinition,
    ) {
        writeErrors(document, root, process, definition)
        writeMessages(document, root, process, definition)
        writeGroupCategories(document, root, process, definition)
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
