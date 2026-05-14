package dev.groknull.bpmner.repair.internal.domain.handlers
import dev.groknull.bpmner.core.BpmnDefinition


import org.springframework.stereotype.Component

@Component
internal class BypassGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "bypassGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val incomingEdge = definition.sequences.singleOrNull { it.targetRef == elementId } ?: return emptyList()
        val outgoingEdge = definition.sequences.singleOrNull { it.sourceRef == elementId } ?: return emptyList()
        val updatedIncoming =
            incomingEdge.copy(
                targetRef = outgoingEdge.targetRef,
                waypoints = listOf(incomingEdge.waypoints.first(), outgoingEdge.waypoints.last()),
            )
        return listOf(
            BpmnPatchOperation(
                type = BpmnPatchOperationType.REPLACE_EDGE,
                edgeId = incomingEdge.id,
                edge = updatedIncoming,
            ),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_EDGE, edgeId = outgoingEdge.id),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_NODE, nodeId = elementId),
        )
    }
}
