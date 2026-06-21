/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnGateway
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.internal.model.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.internal.model.BpmnParallelGateway

/**
 * Chooses the right kind of synthesized join gateway based on the upstream topology.
 *
 * The original (pre-PR-184) repair handlers always synthesized exclusive joins, which is
 * wrong for parallel forks: if the LLM emits a parallel topology that's missing its join,
 * an exclusive merge would silently change "wait for all branches" into "first branch wins".
 * The same risk applies to inclusive forks: an exclusive merge below an OR-fork would turn
 * "wait for whichever branches activated" into "first branch wins".
 *
 * Heuristic: walk back through every incoming edge until the nearest gateway ancestor is
 * found on each path. If every path leads back to *the same* parallel (or inclusive) gateway
 * with no intervening exclusive gateway, the missing join must match. Otherwise default to
 * exclusive — which preserves the historical behaviour for non-fork cases.
 */
internal object JoinGatewayKindSelector {
    fun newJoinGateway(
        definition: BpmnDefinition,
        joinId: String,
        incomingEdges: List<BpmnEdge>,
    ): BpmnNode {
        val ancestors = incomingEdges.map { nearestGatewayAncestor(definition, it.sourceRef) }
        val sharedParallelFork: BpmnParallelGateway? =
            if (ancestors.all { it is BpmnParallelGateway }) {
                ancestors.filterIsInstance<BpmnParallelGateway>().distinctBy { it.id }.singleOrNull()
            } else {
                null
            }
        if (sharedParallelFork != null) return BpmnParallelGateway(id = joinId, name = null)

        val sharedInclusiveFork: BpmnInclusiveGateway? =
            if (ancestors.all { it is BpmnInclusiveGateway }) {
                ancestors.filterIsInstance<BpmnInclusiveGateway>().distinctBy { it.id }.singleOrNull()
            } else {
                null
            }
        if (sharedInclusiveFork != null) return BpmnInclusiveGateway(id = joinId, name = null)

        return BpmnExclusiveGateway(id = joinId, name = null)
    }

    /**
     * Walks back through predecessor edges (BFS) from [startNodeId] and returns the first
     * gateway node encountered. Returns null if no gateway is reachable (e.g. the chain
     * runs back to a start event without crossing a gateway). The visited set prevents
     * cycles caused by loop back-edges.
     */
    private fun nearestGatewayAncestor(
        definition: BpmnDefinition,
        startNodeId: String,
    ): BpmnNode? {
        val nodesById = definition.nodes.associateBy { it.id }
        val incomingByTarget = definition.sequences.groupBy { it.targetRef }
        val visited = mutableSetOf<String>()
        val queue: ArrayDeque<String> = ArrayDeque()
        queue.addLast(startNodeId)
        while (queue.isNotEmpty()) {
            val nodeId = queue.removeFirst()
            val gateway = stepBfs(nodeId, visited, nodesById, incomingByTarget, queue)
            if (gateway != null) return gateway
        }
        return null
    }

    private fun stepBfs(
        nodeId: String,
        visited: MutableSet<String>,
        nodesById: Map<String, BpmnNode>,
        incomingByTarget: Map<String, List<BpmnEdge>>,
        queue: ArrayDeque<String>,
    ): BpmnNode? {
        val freshlyVisited = visited.add(nodeId)
        val node = nodesById[nodeId]
        return when {
            !freshlyVisited || node == null -> {
                null
            }

            node is BpmnGateway -> {
                node
            }

            else -> {
                incomingByTarget[nodeId].orEmpty().forEach { queue.addLast(it.sourceRef) }
                null
            }
        }
    }
}
