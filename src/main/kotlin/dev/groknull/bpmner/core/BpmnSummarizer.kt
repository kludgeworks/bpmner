package dev.groknull.bpmner.core

import org.springframework.stereotype.Component
import java.util.LinkedList
import java.util.Queue

/**
 * Deterministically summarizes a [BpmnDefinition] for semantic alignment checks.
 */
@Component
class BpmnSummarizer {

    fun summarize(definition: BpmnDefinition): BpmnDefinitionSummary {
        val nodeMap = definition.nodes.associateBy { it.id }
        val outgoingFlows = definition.sequences.groupBy { it.sourceRef }

        val visited = TraversalState()
        val queue: Queue<String> = LinkedList()
        
        definition.nodes.filter { it.type == NodeType.START_EVENT }
            .sortedBy { it.id }.forEach { node ->
                if (visited.nodes.add(node.id)) {
                    queue.add(node.id)
                    visited.orderedNodes.add(node)
                }
            }

        while (queue.isNotEmpty()) {
            val nodeId = queue.poll()
            val flows = outgoingFlows[nodeId]?.sortedBy { it.id } ?: emptyList()
            
            for (flow in flows) {
                if (visited.flows.add(flow.id)) visited.orderedFlows.add(flow)
                if (visited.nodes.add(flow.targetRef)) {
                    nodeMap[flow.targetRef]?.let { targetNode ->
                        queue.add(flow.targetRef)
                        visited.orderedNodes.add(targetNode)
                    }
                }
            }
        }

        val unreachableNodes = definition.nodes.filter { it.id !in visited.nodes }.sortedBy { it.id }
        val unreachableFlows = definition.sequences.filter { it.id !in visited.flows }.sortedBy { it.id }

        return BpmnDefinitionSummary(
            processId = definition.processId,
            processName = definition.processName,
            elements = (visited.orderedNodes + unreachableNodes).map { it.toSummary() },
            flows = (visited.orderedFlows + unreachableFlows).map { it.toSummary() },
            unreachableElementIds = unreachableNodes.map { it.id } + unreachableFlows.map { it.id }
        )
    }

    private class TraversalState {
        val nodes = mutableSetOf<String>()
        val flows = mutableSetOf<String>()
        val orderedNodes = mutableListOf<BpmnNode>()
        val orderedFlows = mutableListOf<BpmnEdge>()
    }

    private fun BpmnNode.toSummary() = BpmnSummaryElement(id = id, type = type.name, name = name)
    private fun BpmnEdge.toSummary() = BpmnSummaryFlow(
        id = id,
        sourceRef = sourceRef,
        targetRef = targetRef,
        name = name,
        conditionExpression = conditionExpression
    )
}
