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
    val shapeIdsByNodeId: Map<String, String>,
    val edgeDiagramIdsByEdgeId: Map<String, String>,
) {
    fun objectRefForElementId(elementId: String?): String? {
        if (elementId == null) return null
        if (elementId == processId) return processObjectRef

        return nodeObjectRefs[elementId]
            ?: edgeObjectRefs[elementId]
            ?: shapeIdsByNodeId.entries.firstOrNull { (_, shapeId) -> shapeId == elementId }?.let { (nodeId, _) ->
                nodeObjectRefs[nodeId]
            }
            ?: edgeDiagramIdsByEdgeId.entries.firstOrNull { (_, diagramId) -> diagramId == elementId }?.let { (edgeId, _) ->
                edgeObjectRefs[edgeId]
            }
    }

    fun knownElementIds(): Set<String> =
        buildSet {
            add(processId)
            addAll(nodeObjectRefs.keys)
            addAll(edgeObjectRefs.keys)
            addAll(shapeIdsByNodeId.values)
            addAll(edgeDiagramIdsByEdgeId.values)
        }
}
