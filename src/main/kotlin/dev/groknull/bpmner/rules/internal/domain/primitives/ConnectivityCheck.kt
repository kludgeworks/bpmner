/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

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

        ConnectivityMode.WITHIN_POOL ->
            model.sequenceFlows
                .filter {
                    !it.sourcePool.isNullOrBlank() &&
                        !it.targetPool.isNullOrBlank() &&
                        it.sourcePool != it.targetPool
                }
                .map { metadata.diagnostic(it.id) }

        ConnectivityMode.ACROSS_POOLS ->
            model.messageFlows
                .filter {
                    it.sourcePool.isNullOrBlank() ||
                        it.targetPool.isNullOrBlank() ||
                        it.sourcePool == it.targetPool
                }
                .map { metadata.diagnostic(it.id) }
    }
}
