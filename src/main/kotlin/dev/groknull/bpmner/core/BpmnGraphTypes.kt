/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.core

import jakarta.validation.Valid

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

    fun validateOwnership(): List<String> =
        buildList {
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
    val defaultOwner = ownedGraph.objectOwnersByObjectRef["process"] ?: "phase:main"
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
