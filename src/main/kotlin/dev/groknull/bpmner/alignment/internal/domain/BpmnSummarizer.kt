package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
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

        traverseFromStartEvents(definition, nodeMap, outgoingFlows, visited)

        val unreachableNodes = definition.nodes.filter { it.id !in visited.nodes }.sortedBy { it.id }
        val unreachableFlows = definition.sequences.filter { it.id !in visited.flows }.sortedBy { it.id }

        return BpmnDefinitionSummary(
            processId = definition.processId,
            processName = definition.processName,
            elements = (visited.orderedNodes + unreachableNodes).map { it.toSummary() },
            flows = (visited.orderedFlows + unreachableFlows).map { it.toSummary() },
            unreachableElementIds = unreachableNodes.map { it.id } + unreachableFlows.map { it.id },
        )
    }

    private fun traverseFromStartEvents(
        definition: BpmnDefinition,
        nodeMap: Map<String, BpmnNode>,
        outgoingFlows: Map<String, List<BpmnEdge>>,
        visited: TraversalState,
    ) {
        val queue: Queue<String> = LinkedList()
        definition.nodes
            .filter { it.type == NodeType.START_EVENT }
            .sortedBy { it.id }
            .forEach { node -> enqueue(node, queue, visited) }

        while (queue.isNotEmpty()) {
            val nodeId = queue.poll()
            val flows = outgoingFlows[nodeId]?.sortedBy { it.id } ?: emptyList()
            flows.forEach { flow -> visitFlow(flow, nodeMap, queue, visited) }
        }
    }

    private fun visitFlow(
        flow: BpmnEdge,
        nodeMap: Map<String, BpmnNode>,
        queue: Queue<String>,
        visited: TraversalState,
    ) {
        if (visited.flows.add(flow.id)) visited.orderedFlows.add(flow)
        if (!visited.nodes.add(flow.targetRef)) return
        val targetNode = nodeMap[flow.targetRef] ?: return
        queue.add(flow.targetRef)
        visited.orderedNodes.add(targetNode)
    }

    private fun enqueue(
        node: BpmnNode,
        queue: Queue<String>,
        visited: TraversalState,
    ) {
        if (visited.nodes.add(node.id)) {
            queue.add(node.id)
            visited.orderedNodes.add(node)
        }
    }

    private class TraversalState {
        val nodes = mutableSetOf<String>()
        val flows = mutableSetOf<String>()
        val orderedNodes = mutableListOf<BpmnNode>()
        val orderedFlows = mutableListOf<BpmnEdge>()
    }

    private fun BpmnNode.toSummary() = BpmnSummaryElement(id = id, type = type.name, name = name)

    private fun BpmnEdge.toSummary() =
        BpmnSummaryFlow(
            id = id,
            sourceRef = sourceRef,
            targetRef = targetRef,
            name = name,
            conditionExpression = conditionExpression,
        )
}
