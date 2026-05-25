/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnNodeNamingPolicy
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
import org.springframework.stereotype.Component

/**
 * Flags nodes that require a name (per [BpmnNodeNamingPolicy]) but have a null or blank
 * one. Ports `BpmnDefinitionValidator.validateNames` with byte-identical messages.
 *
 * TODO(#217): Phase 2's `ElementPropertyCheck` Pkl primitive subsumes this compiled rule.
 * Once that lands, delete this file and configure the same behavior via the Pkl catalog.
 */
@Component
internal class RequiredNameRule : BpmnRule {
    override val id: String = "def-required-names"

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.definition.nodes.forEach { node ->
            val outgoingCount = ctx.outgoingCounts[node.id] ?: 0
            val requiresName = BpmnNodeNamingPolicy.requiresName(node = node, outgoingCount = outgoingCount)
            if (requiresName && node.name.isNullOrBlank()) {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-missing-name",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = BpmnNodeNamingPolicy.missingNameMessage(node),
                        elementId = node.id,
                    )
            }
        }

        return diagnostics
    }
}
