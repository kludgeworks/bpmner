/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnNodeNamingPolicy
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
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
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Required Names",
        slug = "required-names",
        category = RuleCategory.DEFINITION,
        intent = "Ensure BPMN elements that require business-readable labels have names.",
        forModellers = "Name activities, events, and gateways when the notation requires a label.",
        forAI = "Populate name fields for nodes that require labels under the BPMN naming policy.",
        targetElements = listOf("bpmn:FlowNode"),
        errorMessages = mapOf("def-missing-name" to "Required BPMN element name is missing."),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

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
