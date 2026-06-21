/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("CyclomaticComplexMethod")

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class TopologyCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: TopologyCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: TopologyCheckConfig,
    ): List<RuleDiagnostic> = when (config.topology) {
        TopologyMode.NO_FAKE_JOIN -> noFakeJoin(model, metadata, config)

        TopologyMode.NO_SUPERFLUOUS -> gatewayChecks(model, metadata, config) { incoming, outgoing, _ ->
            incoming == 1 && outgoing == 1
        }

        TopologyMode.NO_JOIN_FORK -> gatewayChecks(model, metadata, config) { incoming, outgoing, _ ->
            incoming >= 2 && outgoing >= 2
        }

        TopologyMode.CONVERGING_UNNAMED -> gatewayChecks(model, metadata, config) { incoming, outgoing, element ->
            incoming >= 2 && outgoing <= 1 && !element.property("name").isNullOrBlank()
        }
    }

    private fun noFakeJoin(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: TopologyCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.isTask() }
        .filter { element ->
            val id = element.id ?: return@filter false
            val minIncoming = config.minIncoming ?: 2
            val incoming = model.edgesTo[id].orEmpty()
            incoming.size >= minIncoming &&
                incoming.none { flow ->
                    val source = model.elementsById[flow.sourceRef]
                    source != null && source.isGateway() && (model.incomingCounts[source.id] ?: 0) >= 2
                }
        }
        .map { metadata.diagnostic(it.id) }

    private fun gatewayChecks(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: TopologyCheckConfig,
        violates: (incoming: Int, outgoing: Int, element: PrimitiveElement) -> Boolean,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .filter { it.isGateway() }
        .filter { element ->
            val id = element.id ?: return@filter false
            val incoming = model.incomingCounts[id] ?: 0
            val outgoing = model.outgoingCounts[id] ?: 0
            val withinMin = config.minIncoming?.let { incoming >= it } ?: true
            val withinMax = config.maxIncoming?.let { incoming <= it } ?: true
            val withinOutgoingMin = config.minOutgoing?.let { outgoing >= it } ?: true
            val withinOutgoingMax = config.maxOutgoing?.let { outgoing <= it } ?: true
            withinMin && withinMax && withinOutgoingMin && withinOutgoingMax && violates(incoming, outgoing, element)
        }
        .map { metadata.diagnostic(it.id) }
}
