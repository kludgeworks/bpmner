/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import jakarta.validation.Valid

@JsonClassDescription("Rendered BPMN XML with a stable index mapping XML elements back to the typed definition")
data class RenderedBpmn(
    val definition: BpmnDefinition,
    val xml: String,
    @field:Valid
    val elementIndex: BpmnElementIndex,
    val sourceGraph: LaidOutProcessGraph? = null,
)

@JsonClassDescription("Deterministic mapping from rendered BPMN element ids back to typed DTO objects")
data class BpmnElementIndex(
    val processId: String,
    val processObjectRef: String = "process",
    val nodeObjectRefs: Map<String, String>,
    val edgeObjectRefs: Map<String, String>,
) {
    fun objectRefForElementId(elementId: String?): String? {
        if (elementId == null) return null
        if (elementId == processId) return processObjectRef
        return nodeObjectRefs[elementId] ?: edgeObjectRefs[elementId]
    }

    fun knownElementIds(): Set<String> = buildSet {
        add(processId)
        addAll(nodeObjectRefs.keys)
        addAll(edgeObjectRefs.keys)
    }
}
