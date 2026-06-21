/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata

internal class PresenceCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PresenceCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    // The dispatcher signature is uniform across primitives. PresenceCheckConfig is currently a
    // marker, so this evaluator has no config fields to read.
    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PresenceCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .map { metadata.diagnostic(it.id) }
}
