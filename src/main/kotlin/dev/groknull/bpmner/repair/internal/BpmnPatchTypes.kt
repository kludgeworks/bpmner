package dev.groknull.bpmner.repair.internal

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import jakarta.validation.constraints.NotEmpty

enum class BpmnPatchOperationType {
    SET_NODE_NAME,
    SET_EDGE_LABEL,
    ADD_NODE,
    REMOVE_NODE,
    REPLACE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    REPLACE_EDGE,
}

@JsonClassDescription("Single targeted repair operation on a BPMN definition")
data class BpmnPatchOperation(
    @get:JsonPropertyDescription("Operation type discriminator")
    val type: BpmnPatchOperationType,
    @get:JsonPropertyDescription("Node ID — required for SET_NODE_NAME, REMOVE_NODE, REPLACE_NODE")
    val nodeId: String? = null,
    @get:JsonPropertyDescription("Edge ID — required for SET_EDGE_LABEL, REMOVE_EDGE, REPLACE_EDGE")
    val edgeId: String? = null,
    @get:JsonPropertyDescription("New name value for SET_NODE_NAME")
    val name: String? = null,
    @get:JsonPropertyDescription("New label value for SET_EDGE_LABEL")
    val label: String? = null,
    @get:JsonPropertyDescription("Full BpmnNode for ADD_NODE or REPLACE_NODE")
    val node: BpmnNode? = null,
    @get:JsonPropertyDescription("Full BpmnEdge for ADD_EDGE or REPLACE_EDGE")
    val edge: BpmnEdge? = null,
)

@JsonClassDescription("Targeted repair patch — expresses minimal changes to fix specific diagnostics without a full graph rewrite")
data class BpmnRepairPatch(
    @field:NotEmpty
    @get:JsonPropertyDescription("Ordered list of patch operations to apply")
    val operations: List<BpmnPatchOperation>,
    @get:JsonPropertyDescription("Human-readable summary of the patch intent")
    val reason: String? = null,
)

sealed class PatchApplicationResult {
    data class Success(val definition: BpmnDefinition) : PatchApplicationResult()
    data class Failure(val reason: String) : PatchApplicationResult()
    data object NoOp : PatchApplicationResult()
}
