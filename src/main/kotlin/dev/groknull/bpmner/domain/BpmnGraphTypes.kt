/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.domain

import jakarta.validation.Valid
import org.springframework.ai.tool.annotation.Tool

internal const val MAIN_PHASE_OWNER = "phase:main"

// --- Pipeline graph types shared across composition, repair, and layout stages ---

data class ComposedProcessGraph(
    val definition: BpmnDefinition,
    val objectOwnersByObjectRef: Map<String, String>,
)

data class OwnedElementGraph(
    val composedGraph: ComposedProcessGraph,
    val elementOwnersByElementId: Map<String, String>,
    val objectOwnersByObjectRef: Map<String, String>,
) {
    val definition: BpmnDefinition
        get() = composedGraph.definition

    fun ownerForElementId(elementId: String?): String? = elementOwnersByElementId[elementId]

    fun ownerForObjectRef(objectRef: String?): String? = objectOwnersByObjectRef[objectRef]
}

data class LaidOutProcessGraph(
    val ownedGraph: OwnedElementGraph,
    @field:Valid
    val definition: BpmnDefinition,
) {
    fun ownerForElementId(elementId: String?): String? = ownedGraph.ownerForElementId(elementId)

    fun ownerForObjectRef(objectRef: String?): String? = ownedGraph.ownerForObjectRef(objectRef)

    @Tool
    fun validateOwnership(): List<String> = buildList {
        if (ownedGraph.objectOwnersByObjectRef.isEmpty()) return@buildList
        definition.nodes.forEach { node ->
            if (ownerForElementId(node.id) == null) add("Node '${node.id}' has no owner assignment")
        }
        definition.sequences.forEach { edge ->
            if (ownerForElementId(edge.id) == null) add("Edge '${edge.id}' has no owner assignment")
        }
    }
}

fun LaidOutProcessGraph.withUpdatedDefinition(newDefinition: BpmnDefinition): LaidOutProcessGraph {
    if (ownedGraph.objectOwnersByObjectRef.isEmpty()) {
        val emptyOwnedGraph =
            OwnedElementGraph(
                composedGraph = ownedGraph.composedGraph.copy(definition = newDefinition),
                elementOwnersByElementId = emptyMap(),
                objectOwnersByObjectRef = emptyMap(),
            )
        return LaidOutProcessGraph(ownedGraph = emptyOwnedGraph, definition = newDefinition)
    }
    val defaultOwner = ownedGraph.objectOwnersByObjectRef["process"] ?: MAIN_PHASE_OWNER
    val baseObjectOwners = ownedGraph.objectOwnersByObjectRef
    val updatedObjectOwners: Map<String, String> =
        baseObjectOwners +
            newDefinition.nodes
                .filter { "nodes[id=${it.id}]" !in baseObjectOwners }
                .associate { "nodes[id=${it.id}]" to defaultOwner } +
            newDefinition.sequences
                .filter { "sequences[id=${it.id}]" !in baseObjectOwners }
                .associate { "sequences[id=${it.id}]" to defaultOwner }
    val newElementOwners: Map<String, String> =
        buildMap {
            put(newDefinition.processId, updatedObjectOwners["process"] ?: defaultOwner)
            newDefinition.nodes.forEach { node ->
                val owner = updatedObjectOwners["nodes[id=${node.id}]"] ?: defaultOwner
                put(node.id, owner)
                put("${node.id}_di", owner)
            }
            newDefinition.sequences.forEach { edge ->
                val owner = updatedObjectOwners["sequences[id=${edge.id}]"] ?: defaultOwner
                put(edge.id, owner)
                put("${edge.id}_di", owner)
            }
        }
    val updatedOwnedGraph =
        OwnedElementGraph(
            composedGraph =
            ownedGraph.composedGraph.copy(
                definition = newDefinition,
                objectOwnersByObjectRef = updatedObjectOwners,
            ),
            elementOwnersByElementId = newElementOwners,
            objectOwnersByObjectRef = updatedObjectOwners,
        )
    return LaidOutProcessGraph(ownedGraph = updatedOwnedGraph, definition = newDefinition)
}
