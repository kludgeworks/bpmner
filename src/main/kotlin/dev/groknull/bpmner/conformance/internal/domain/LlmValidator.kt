/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance.internal.domain

import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.SanctionedArchitectureException
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnRuleGuidancePort
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.springframework.stereotype.Component

@Deprecated(
    message = "LLM-rule validation has moved to LlmRuleAgent in Phase 2C (#240). " +
        "The remaining guidance-provider role (getLlmRuleGuidance() used by BpmnRepairPromptFactory) " +
        "will move to a `rules`-side helper in a follow-up phase. " +
        "Do not extend this class — the GOAP-shaped LlmRuleAgent is the replacement.",
)
@Component
@SanctionedArchitectureException(reason = "Deprecated validation class needing temporary ruleset package access (ADR-010)")
internal class LlmValidator(
    private val ruleRegistry: RuleRegistry,
) : BpmnRuleGuidancePort {
    fun validate(
        _definition: BpmnDefinition,
        _graph: LaidOutProcessGraph,
        _promptRunner: PromptRunner,
    ): List<BpmnDiagnostic> {
        val llmRules = ruleRegistry.llmRuleSpecs()
        if (llmRules.isEmpty()) return emptyList()

        // In production this would invoke an LLM agent for heuristic validation.
        // Currently, rule guidance is passed to the repair prompt factory instead
        // so the repair agent performs just-in-time validation and repair.

        return emptyList()
    }

    override fun getLlmRuleGuidance(): String {
        val llmRules = ruleRegistry.llmRuleSpecs()
        if (llmRules.isEmpty()) return ""

        return buildString {
            appendLine("General Workflow Rule Guidance (Heuristic):")
            for (rule in llmRules) {
                val metadata = rule.metadata
                appendLine("## ${rule.metadata.id}: ${metadata.name}")
                appendLine("Intent: ${metadata.intent}")
                appendLine("Guidance: ${metadata.forAI}")
                appendLine()
            }
        }
    }
}
