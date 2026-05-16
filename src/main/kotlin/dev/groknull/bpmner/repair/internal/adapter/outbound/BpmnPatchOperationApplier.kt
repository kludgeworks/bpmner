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

package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType

internal sealed class OperationResult {
    data class Changed(
        val definition: BpmnDefinition,
    ) : OperationResult()

    data object Unchanged : OperationResult()

    data class Invalid(
        val reason: String,
    ) : OperationResult()
}

internal object BpmnPatchOperationApplier {
    fun applyOperation(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult =
        when (op.type) {
            BpmnPatchOperationType.SET_NODE_NAME -> applySetNodeName(definition, op)
            BpmnPatchOperationType.SET_EDGE_LABEL -> applySetEdgeLabel(definition, op)
            BpmnPatchOperationType.ADD_NODE -> applyAddNode(definition, op)
            BpmnPatchOperationType.REMOVE_NODE -> applyRemoveNode(definition, op)
            BpmnPatchOperationType.REPLACE_NODE -> applyReplaceNode(definition, op)
            BpmnPatchOperationType.ADD_EDGE -> applyAddEdge(definition, op)
            BpmnPatchOperationType.REMOVE_EDGE -> applyRemoveEdge(definition, op)
            BpmnPatchOperationType.REPLACE_EDGE -> applyReplaceEdge(definition, op)
        }

    private fun applySetNodeName(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val nodeId = op.nodeId ?: return OperationResult.Invalid("SET_NODE_NAME requires nodeId")
        val node =
            definition.nodes.firstOrNull { it.id == nodeId }
                ?: return OperationResult.Invalid("SET_NODE_NAME: unknown nodeId '$nodeId'")
        val name = BpmnNodeNamingPolicy.normalize(op.name)
        if (name == null && node.requiresName(definition)) {
            return OperationResult.Invalid("SET_NODE_NAME name must not be blank for ${node.type}")
        }
        if (BpmnNodeNamingPolicy.normalize(node.name) == name) return OperationResult.Unchanged
        val updated =
            definition.copy(nodes = definition.nodes.map { if (it.id == nodeId) it.copy(name = name) else it })
        return OperationResult.Changed(updated)
    }

    private fun BpmnNode.requiresName(definition: BpmnDefinition): Boolean =
        BpmnNodeNamingPolicy.requiresName(
            node = this,
            outgoingCount = definition.sequences.count { it.sourceRef == id },
        )

    private fun applySetEdgeLabel(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val edgeId = op.edgeId ?: return OperationResult.Invalid("SET_EDGE_LABEL requires edgeId")
        val edge =
            definition.sequences.firstOrNull { it.id == edgeId }
                ?: return OperationResult.Invalid("SET_EDGE_LABEL: unknown edgeId '$edgeId'")
        if (edge.name == op.label) return OperationResult.Unchanged
        val updated =
            definition.copy(
                sequences = definition.sequences.map { if (it.id == edgeId) it.copy(name = op.label) else it },
            )
        return OperationResult.Changed(updated)
    }

    private fun applyAddNode(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val node = op.node ?: return OperationResult.Invalid("ADD_NODE requires node")
        if (definition.nodes.any { it.id == node.id }) {
            return OperationResult.Invalid("ADD_NODE: node id '${node.id}' already exists")
        }
        return OperationResult.Changed(definition.copy(nodes = definition.nodes + node))
    }

    private fun applyRemoveNode(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val nodeId = op.nodeId ?: return OperationResult.Invalid("REMOVE_NODE requires nodeId")
        if (definition.nodes.none { it.id == nodeId }) {
            return OperationResult.Invalid("REMOVE_NODE: unknown nodeId '$nodeId'")
        }
        val referencingEdges = definition.sequences.filter { it.sourceRef == nodeId || it.targetRef == nodeId }
        if (referencingEdges.isNotEmpty()) {
            return OperationResult.Invalid(
                "REMOVE_NODE: nodeId '$nodeId' still referenced by edges: " +
                    "${referencingEdges.map { it.id }}",
            )
        }
        return OperationResult.Changed(definition.copy(nodes = definition.nodes.filter { it.id != nodeId }))
    }

    private fun applyReplaceNode(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val nodeId = op.nodeId ?: return OperationResult.Invalid("REPLACE_NODE requires nodeId")
        val replacement = op.node ?: return OperationResult.Invalid("REPLACE_NODE requires node")
        if (replacement.id != nodeId) {
            return OperationResult.Invalid(
                "REPLACE_NODE: replacement node id '${replacement.id}' must match nodeId '$nodeId'",
            )
        }
        val existing =
            definition.nodes.firstOrNull { it.id == nodeId }
                ?: return OperationResult.Invalid("REPLACE_NODE: unknown nodeId '$nodeId'")
        if (existing == replacement) return OperationResult.Unchanged
        return OperationResult.Changed(
            definition.copy(nodes = definition.nodes.map { if (it.id == nodeId) replacement else it }),
        )
    }

    private fun applyAddEdge(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val edge = op.edge ?: return OperationResult.Invalid("ADD_EDGE requires edge")
        if (definition.sequences.any { it.id == edge.id }) {
            return OperationResult.Invalid("ADD_EDGE: edge id '${edge.id}' already exists")
        }
        val nodeIds = definition.nodes.map { it.id }.toSet()
        if (edge.sourceRef !in nodeIds) {
            return OperationResult.Invalid("ADD_EDGE: sourceRef '${edge.sourceRef}' does not reference a known node")
        }
        if (edge.targetRef !in nodeIds) {
            return OperationResult.Invalid("ADD_EDGE: targetRef '${edge.targetRef}' does not reference a known node")
        }
        return OperationResult.Changed(definition.copy(sequences = definition.sequences + edge))
    }

    private fun applyRemoveEdge(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val edgeId = op.edgeId ?: return OperationResult.Invalid("REMOVE_EDGE requires edgeId")
        if (definition.sequences.none { it.id == edgeId }) {
            return OperationResult.Invalid("REMOVE_EDGE: unknown edgeId '$edgeId'")
        }
        return OperationResult.Changed(definition.copy(sequences = definition.sequences.filter { it.id != edgeId }))
    }

    private fun applyReplaceEdge(
        definition: BpmnDefinition,
        op: BpmnPatchOperation,
    ): OperationResult {
        val edgeId = op.edgeId ?: return OperationResult.Invalid("REPLACE_EDGE requires edgeId")
        val replacement = op.edge ?: return OperationResult.Invalid("REPLACE_EDGE requires edge")
        if (replacement.id != edgeId) {
            return OperationResult.Invalid(
                "REPLACE_EDGE: replacement edge id '${replacement.id}' must match edgeId '$edgeId'",
            )
        }
        val existing =
            definition.sequences.firstOrNull { it.id == edgeId }
                ?: return OperationResult.Invalid("REPLACE_EDGE: unknown edgeId '$edgeId'")
        if (existing == replacement) return OperationResult.Unchanged
        return OperationResult.Changed(
            definition.copy(sequences = definition.sequences.map { if (it.id == edgeId) replacement else it }),
        )
    }
}
