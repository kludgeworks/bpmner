/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

internal class PresenceCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PresenceCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    @Suppress("UNUSED_PARAMETER")
    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PresenceCheckConfig,
    ): List<RuleDiagnostic> = metadata.targetedElements(model)
        .map { metadata.diagnostic(it.id) }
}
