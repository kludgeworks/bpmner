/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class ConnectivityCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: ConnectivityCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

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
        ConnectivityMode.WITHIN_POOL -> {
            if (!model.supports(ModelCapability.POOLS_AND_LANES)) {
                emptyList()
            } else {
                model.sequenceFlows
                    .filter {
                        !it.sourcePool.isNullOrBlank() &&
                            !it.targetPool.isNullOrBlank() &&
                            it.sourcePool != it.targetPool
                    }
                    .map { metadata.diagnostic(it.id) }
            }
        }

        ConnectivityMode.ACROSS_POOLS -> {
            if (!model.supports(ModelCapability.POOLS_AND_LANES)) {
                emptyList()
            } else {
                model.messageFlows
                    .filter {
                        it.sourcePool.isNullOrBlank() ||
                            it.targetPool.isNullOrBlank() ||
                            it.sourcePool == it.targetPool
                    }
                    .map { metadata.diagnostic(it.id) }
            }
        }
    }
}
