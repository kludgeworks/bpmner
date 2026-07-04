/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment.internal.domain

import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnGateway
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNodeNamingPolicy
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.typeName
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

        // Structural routing gateways — converging joins/barriers, i.e. everything the naming
        // policy does not require a label for — are BPMN scaffolding, not semantic work. The
        // deterministic ruleset already governs them: `RequiredNameRule` exempts non-forking
        // gateways via the same `BpmnNodeNamingPolicy`, mirroring bpmnlint's `label-required` join
        // exemption. They must therefore not be handed to the LLM alignment validator at all —
        // otherwise it flags each unnamed join as UNSUPPORTED and fails an otherwise-correct
        // diagram (BpmnAlignmentPostChecker.blockOnUnsupportedElements). Removing them from the
        // element list is not enough: a strict model still infers "unlisted joining gateways" from
        // the flow lines, so we also SPLICE them out of the flows (rewiring `A -> join -> B` into
        // `A -> B`, preserving the branch's condition/label), leaving no trace for the aligner.
        val routingGatewayIds =
            definition.nodes
                .filter { it.isUnlabeledRoutingGateway(outgoingFlows[it.id]?.size ?: 0) }
                .map { it.id }
                .toSet()

        fun isSemanticElement(node: BpmnNode): Boolean = node.id !in routingGatewayIds

        val reachableSemanticNodes = visited.orderedNodes.filter(::isSemanticElement)
        val unreachableSemanticNodes = unreachableNodes.filter(::isSemanticElement)
        val splicedFlows = spliceOutRoutingGateways(visited.orderedFlows + unreachableFlows, routingGatewayIds, outgoingFlows)

        return BpmnDefinitionSummary(
            processId = definition.processId,
            processName = definition.processName,
            elements = (reachableSemanticNodes + unreachableSemanticNodes).map { it.toSummary() },
            flows = splicedFlows.map { it.toSummary() },
            unreachableElementIds =
            unreachableSemanticNodes.map { it.id } +
                splicedFlows.filter { it.id !in visited.flows }.map { it.id },
        )
    }

    /**
     * Removes [routingGatewayIds] from the flow graph by contraction: any edge whose target is a
     * routing gateway is redirected through it (and any chained routing gateways) to the first
     * genuine (kept) target(s), preserving the redirected edge's id, name and condition — those
     * carry the branch semantics from the diverging decision. Edges originating inside a removed
     * gateway are dropped as internal. Input order is preserved.
     */
    private fun spliceOutRoutingGateways(
        flows: List<BpmnEdge>,
        routingGatewayIds: Set<String>,
        outgoingBySource: Map<String, List<BpmnEdge>>,
    ): List<BpmnEdge> {
        if (routingGatewayIds.isEmpty()) return flows
        val result = mutableListOf<BpmnEdge>()
        for (edge in flows) {
            when {
                // Edge originates inside a removed gateway → internal, dropped.
                edge.sourceRef in routingGatewayIds -> Unit
                // Both endpoints kept → passed through unchanged.
                edge.targetRef !in routingGatewayIds -> result += edge
                // Target is a routing gateway → redirect to the first kept target(s).
                else -> {
                    val keptTargets = keptTargetsThrough(edge.targetRef, routingGatewayIds, outgoingBySource)
                    keptTargets.forEachIndexed { index, target ->
                        val id = if (keptTargets.size == 1) edge.id else "${edge.id}-$index"
                        result += edge.copy(id = id, targetRef = target)
                    }
                }
            }
        }
        return result
    }

    /**
     * Breadth-first walk from [start] through routing gateways only, collecting the ids of the
     * first non-routing (kept) nodes reached. Cycle-safe via a visited set.
     */
    private fun keptTargetsThrough(
        start: String,
        routingGatewayIds: Set<String>,
        outgoingBySource: Map<String, List<BpmnEdge>>,
    ): List<String> {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { add(start) }
        val kept = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!visited.add(node)) continue
            for (out in outgoingBySource[node].orEmpty()) {
                if (out.targetRef in routingGatewayIds) queue.add(out.targetRef) else kept += out.targetRef
            }
        }
        return kept.distinct()
    }

    /**
     * True for routing gateways that carry no semantic label: converging exclusive/inclusive joins
     * and every parallel gateway (fork or join). Delegates to the same [BpmnNodeNamingPolicy] the
     * deterministic `RequiredNameRule` uses, so "named decision vs. structural scaffolding" stays
     * single-sourced and matches bpmnlint's `label-required` join exemption. Forking
     * exclusive/inclusive/event gateways (named decisions) return false and remain in the summary.
     */
    private fun BpmnNode.isUnlabeledRoutingGateway(outgoingCount: Int): Boolean {
        return this is BpmnGateway && !BpmnNodeNamingPolicy.requiresName(this, outgoingCount)
    }

    private fun traverseFromStartEvents(
        definition: BpmnDefinition,
        nodeMap: Map<String, BpmnNode>,
        outgoingFlows: Map<String, List<BpmnEdge>>,
        visited: TraversalState,
    ) {
        val queue: Queue<String> = LinkedList()
        // Roots: every start event, plus event-subprocess markers — an event subprocess has no
        // incoming flow by design (it is triggered by its inner start), so it would otherwise be
        // reported unreachable. Its inner nodes are reached via the inner start, itself a root.
        definition.nodes
            .filter { it is BpmnStartEvent || (it is BpmnSubProcess && it.triggeredByEvent) }
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

    private fun BpmnNode.toSummary() = BpmnSummaryElement(id = id, type = typeName, name = name)

    private fun BpmnEdge.toSummary() = BpmnSummaryFlow(
        id = id,
        sourceRef = sourceRef,
        targetRef = targetRef,
        name = name,
        conditionExpression = conditionExpression,
    )
}
