package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode

internal data class BpmnRepairPatch(
    val operations: List<BpmnPatchOperation> = emptyList(),
    val reason: String? = null,
)

internal data class BpmnPatchOperation(
    val type: BpmnPatchOperationType,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val node: BpmnNode? = null,
    val edge: BpmnEdge? = null,
)

internal enum class BpmnPatchOperationType {
    SET_NODE_NAME,
    SET_EDGE_LABEL,
    ADD_NODE,
    REMOVE_NODE,
    REPLACE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    REPLACE_EDGE,
}

internal sealed class PatchApplicationResult {
    data class Success(val definition: BpmnDefinition) : PatchApplicationResult()
    data object NoOp : PatchApplicationResult()
    data class Failure(val reason: String) : PatchApplicationResult()
}
