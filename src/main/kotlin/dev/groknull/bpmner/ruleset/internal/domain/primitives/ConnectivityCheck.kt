/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEventSubProcess
import dev.groknull.bpmner.bpmn.BpmnTask
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class ConnectivityCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: ConnectivityCheckConfig,
    ): List<RuleDiagnostic> = if (config.mode == ConnectivityMode.WEAK_COMPONENTS_BY_SCOPE) {
        weakComponents(ctx, metadata)
    } else {
        evaluate(ctx.toPrimitiveModelContext(), metadata, config)
    }

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: ConnectivityCheckConfig,
    ): List<RuleDiagnostic> = when (config.mode) {
        ConnectivityMode.NO_INCOMING -> metadata.targetedElements(model)
            .filter { (model.incomingCounts[it.id] ?: 0) > 0 }
            .map { metadata.diagnostic(it.id) }

        ConnectivityMode.FLOWS_NAMED -> metadata.targetedElements(model)
            .filter { it.typeName == "bpmn:SequenceFlow" }
            .filter { it.property("name").isNullOrBlank() }
            .map { metadata.diagnostic(it.id) }

        // Flags a diverging gateway (the modeller's control point) when any of its outgoing
        // sequence flows is unnamed — narrower than FLOWS_NAMED, which flags every unnamed flow
        // regardless of source. A gateway is diverging only with more than one outgoing flow, so a
        // converging/merge gateway (a single outgoing flow) is never flagged even when that flow is
        // unnamed. Reads edgesFrom (grouped by sourceRef) keyed on the gateway id.
        ConnectivityMode.OUTGOING_FLOWS_NAMED -> metadata.targetedElements(model)
            .filter { gateway ->
                val outgoing = model.edgesFrom[gateway.id].orEmpty()
                outgoing.size > 1 && outgoing.any { it.name.isNullOrBlank() }
            }
            .map { metadata.diagnostic(it.id) }

        // WITHIN_POOL / ACROSS_POOLS are dormant in production until participants, lanes, and
        // `sourcePool`/`targetPool` flow fields land in the BPMN model (#196). Until then,
        // production flows have null pool fields — every flow would be flagged — so we
        // short-circuit on the capability bit.
        ConnectivityMode.WITHIN_POOL -> invalidWithinPoolFlows(model, metadata)

        ConnectivityMode.ACROSS_POOLS -> invalidAcrossPoolFlows(model, metadata)

        ConnectivityMode.WEAK_COMPONENTS_BY_SCOPE -> emptyList()
    }

    private fun invalidWithinPoolFlows(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> {
        if (!model.supports(ModelCapability.POOLS_AND_LANES)) return emptyList()
        return model.sequenceFlows
            .filter {
                !it.sourcePool.isNullOrBlank() &&
                    !it.targetPool.isNullOrBlank() &&
                    it.sourcePool != it.targetPool
            }
            .map { metadata.diagnostic(it.id) }
    }

    private fun invalidAcrossPoolFlows(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
    ): List<RuleDiagnostic> {
        if (!model.supports(ModelCapability.POOLS_AND_LANES)) return emptyList()
        return model.messageFlows
            .filter {
                it.sourcePool.isNullOrBlank() ||
                    it.targetPool.isNullOrBlank() ||
                    it.sourcePool == it.targetPool
            }
            .map { metadata.diagnostic(it.id) }
    }

    private fun weakComponents(ctx: BpmnDefinitionContext, metadata: RuleMetadata): List<RuleDiagnostic> {
        val boundaryIds = ctx.definition.nodes.filterIsInstance<BpmnBoundaryEvent>().map { it.id }.toSet()
        return ctx.definition.nodes
            .filterNot { it is BpmnBoundaryEvent || it is BpmnEventSubProcess || (it is BpmnTask && it.isForCompensation) }
            .groupBy { it.parentRef }
            .flatMap { (parentRef, nodes) ->
                if (nodes.size < 2) return@flatMap emptyList()
                val disconnected = disconnectedNodeIds(ctx, boundaryIds, parentRef, nodes.map { it.id })
                if (disconnected.isEmpty()) {
                    emptyList()
                } else {
                    listOf(metadata.diagnostic(parentRef ?: "process", disconnected.joinToString(", ")))
                }
            }
    }

    private fun disconnectedNodeIds(
        ctx: BpmnDefinitionContext,
        boundaryIds: Set<String>,
        parentRef: String?,
        nodeIds: List<String>,
    ): List<String> {
        val ids = nodeIds.toSet()
        val adjacent = adjacencyByNode(ctx, parentRef, ids)
        val handlerTargets = ctx.definition.sequences
            .filter { it.parentRef == parentRef && it.sourceRef in boundaryIds && it.targetRef in ids }
            .map { it.targetRef }
        return (ids - reachableNodes(adjacent, handlerTargets + nodeIds.first())).sorted()
    }

    private fun adjacencyByNode(
        ctx: BpmnDefinitionContext,
        parentRef: String?,
        nodeIds: Set<String>,
    ): Map<String, MutableSet<String>> = nodeIds.associateWith { mutableSetOf<String>() }.also { adjacent ->
        ctx.definition.sequences
            .filter { it.parentRef == parentRef && it.sourceRef in nodeIds && it.targetRef in nodeIds }
            .forEach { edge ->
                adjacent.getValue(edge.sourceRef).add(edge.targetRef)
                adjacent.getValue(edge.targetRef).add(edge.sourceRef)
            }
    }

    private fun reachableNodes(
        adjacent: Map<String, Set<String>>,
        initialNodes: List<String>,
    ): Set<String> {
        val visited = initialNodes.toMutableSet()
        val pending = ArrayDeque(visited)
        while (pending.isNotEmpty()) {
            adjacent.getValue(pending.removeFirst()).filter { visited.add(it) }.forEach(pending::addLast)
        }
        return visited
    }
}
