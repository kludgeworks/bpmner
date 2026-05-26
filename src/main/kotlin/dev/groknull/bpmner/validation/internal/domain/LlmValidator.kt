/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import org.springframework.stereotype.Component

@Deprecated(
    message = "LLM-rule validation has moved to LlmRuleAgent in Phase 2C (#240). " +
        "The remaining guidance-provider role (getLlmRuleGuidance() used by BpmnRepairPromptFactory) " +
        "will move to a `rules`-side helper in a follow-up phase. " +
        "Do not extend this class — the GOAP-shaped LlmRuleAgent is the replacement.",
)
@Component
internal class LlmValidator(
    private val ruleRegistry: RuleRegistry,
) : BpmnRuleGuidancePort {
    fun validate(
        _definition: BpmnDefinition,
        _graph: LaidOutProcessGraph,
        _promptRunner: PromptRunner,
    ): List<BpmnDiagnostic> {
        val llmRules = ruleRegistry.activeRules().filter(::isLlmJudged)
        if (llmRules.isEmpty()) return emptyList()

        // In production this would invoke an LLM agent for heuristic validation.
        // Currently, rule guidance is passed to the repair prompt factory instead
        // so the repair agent performs just-in-time validation and repair.

        return emptyList()
    }

    override fun getLlmRuleGuidance(): String {
        val llmRules = ruleRegistry.activeRules().filter(::isLlmJudged)
        if (llmRules.isEmpty()) return ""

        return buildString {
            appendLine("General Workflow Rule Guidance (Heuristic):")
            for (rule in llmRules) {
                val metadata = rule.metadata
                appendLine("## ${rule.id}: ${metadata.name}")
                appendLine("Intent: ${metadata.intent}")
                appendLine("Guidance: ${metadata.forAI}")
                appendLine()
            }
        }
    }

    private fun isLlmJudged(rule: BpmnRule): Boolean = rule.metadata.checkPrimitive == "LlmCheck"
}
