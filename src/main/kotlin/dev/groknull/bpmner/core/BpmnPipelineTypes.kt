package dev.groknull.bpmner.core
import dev.groknull.bpmner.layout.LaidOutProcessGraph

import dev.groknull.bpmner.validation.BpmnDiagnostic



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

data class ValidatedBpmnXml(
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
    val repairAttempts: Int = 0,
)

data class AutoFixedBpmnXml(
    val xml: String,
    val autoFixResult: BpmnAutoFixResult? = null,
)

data class LayoutedBpmnXml(
    val xml: String,
)

data class FinalValidatedBpmnXml(
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)
