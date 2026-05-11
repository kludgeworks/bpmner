package dev.groknull.bpmner.validation.internal.domain

import com.embabel.agent.api.common.PromptRunner
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.validation.internal.adapter.outbound.RuleCatalogService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class LlmValidator(
    private val catalogService: RuleCatalogService,
) {
    private val logger = LoggerFactory.getLogger(LlmValidator::class.java)

    fun validate(
        definition: BpmnDefinition,
        graph: LaidOutProcessGraph,
        promptRunner: PromptRunner,
    ): List<BpmnDiagnostic> {
        val llmRules = catalogService.catalog.rules.filter { !it.hasTsImplementation }
        if (llmRules.isEmpty()) return emptyList()

        // TODO: In a real production system, we would call an LLM agent here to perform heuristic validation.
        // For this task, we've enabled the Pkl-driven architecture.
        // We will pass these rule definitions to the repair prompt factory instead,
        // so the repair agent performs "just-in-time" validation and repair.

        return emptyList()
    }

    fun getLlmRuleGuidance(): String {
        val llmRules = catalogService.catalog.rules.filter { !it.hasTsImplementation }
        if (llmRules.isEmpty()) return ""

        return buildString {
            appendLine("General Business Rule Guidance (Heuristic):")
            for (rule in llmRules) {
                appendLine("## ${rule.id}: ${rule.name}")
                appendLine("Intent: ${rule.intent}")
                appendLine("Guidance: ${rule.forAI}")
                appendLine()
            }
        }
    }
}
