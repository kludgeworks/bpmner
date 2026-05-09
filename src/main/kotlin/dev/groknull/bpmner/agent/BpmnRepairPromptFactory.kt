package dev.groknull.bpmner.agent

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import org.springframework.stereotype.Component

@Component
class BpmnRepairPromptFactory(
    private val config: BpmnConfig,
    private val bpmnLintService: BpmnLintService,
    private val fingerprints: BpmnFingerprintService,
    private val normalizer: BpmnDiagnosticNormalizer,
) {
    fun initialMessages(request: BpmnRequest, definition: BpmnDefinition): List<Message> = listOf(
        UserMessage(request.generationPrompt()),
        AssistantMessage(fingerprints.serializeDefinition(definition)),
    )

    fun patchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): String = buildString {
        appendLine("The following diagnostics can be fixed with targeted name or label patches.")
        appendLine("Return a BpmnRepairPatch with the minimum operations needed to fix these issues.")
        appendLine("Do not rewrite the whole graph — only include operations that directly address the listed diagnostics.")
        appendLine()
        appendLine("Current canonical BpmnDefinition JSON:")
        appendLine(fingerprints.serializeDefinition(definition))
        appendLine()
        appendLine("Diagnostics to fix:")
        diagnostics.forEach { diagnostic ->
            appendLine("- ${normalizer.formatDiagnostic(diagnostic)}")
        }
    }

    fun targetedLabelPatchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): String {
        val affectedIds = diagnostics.mapNotNull { it.elementId }.distinct()
        val affectedNodes = definition.nodes.filter { it.id in affectedIds }
        val ruleDocs = bpmnLintService.ruleDocs(diagnostics.mapNotNull { it.rule }.distinct().toSet())
        return buildString {
            appendLine("Fix the following BPMN element label violations.")
            appendLine("Return only a BpmnRepairPatch with SET_NODE_NAME operations for the listed elements — do not modify any other nodes.")
            appendLine()
            appendLine("Affected elements:")
            for (node in affectedNodes) {
                val diag = diagnostics.first { it.elementId == node.id }
                appendLine("  - id=${node.id}, current name=\"${node.name}\", rule=${diag.rule}")
                appendLine("    violation: ${diag.message}")
                ruleDocs[diag.rule]?.let { appendLine("    rule guidance: $it") }
                if (diag.rule?.contains("name-02") == true && node.name != null) {
                    val relevant = config.repair.abbreviations.entries.filter { node.name.contains(it.key) }
                    if (relevant.isNotEmpty()) {
                        appendLine("    domain glossary: ${relevant.joinToString { "${it.key}=${it.value}" }}")
                    }
                }
            }
        }
    }

    fun fullRepairFeedback(attempt: BpmnRepairAttempt): String =
        fullRepairFeedback(
            definition = attempt.definition,
            renderedXml = attempt.evaluation.rendered?.xml ?: renderFailureContext(attempt.evaluation),
            diagnostics = attempt.diagnostics,
        )

    fun lintRuleDocsPrompt(diagnostics: List<BpmnDiagnostic>): PromptContributor? {
        val lintRules = diagnostics
            .asSequence()
            .filter { it.source == BpmnDiagnosticSource.LINT }
            .mapNotNull { it.rule }
            .distinct()
            .toList()
        if (lintRules.isEmpty()) {
            return null
        }

        val docs = bpmnLintService.ruleDocs(lintRules)
        if (docs.isEmpty()) {
            return null
        }

        val content = buildString {
            appendLine("BPMNER lint rule documentation for current violations:")
            appendLine()
            docs.toSortedMap().forEach { (rule, markdown) ->
                appendLine("## $rule")
                appendLine()
                appendLine(markdown.trim())
                appendLine()
            }
        }.trim()

        return if (content.isBlank()) null else PromptContributor.fixed(content)
    }

    private fun fullRepairFeedback(
        definition: BpmnDefinition,
        renderedXml: String,
        diagnostics: List<BpmnDiagnostic>,
    ): String = buildString {
        appendLine("The BPMN definition needs repair. Return the full corrected BpmnDefinition object.")
        appendLine()
        appendLine("Use the typed BPMN definition as the canonical edit surface.")
        appendLine("Use the rendered BPMN XML only as supporting context when diagnostics refer to rendered elements.")
        appendLine()
        appendLine("Current canonical BpmnDefinition JSON:")
        appendLine(fingerprints.serializeDefinition(definition))
        appendLine()
        appendLine("Rendered BPMN XML:")
        appendLine(renderedXml)
        appendLine()
        val scopes = diagnostics.mapNotNull { it.repairScope }.distinct()
        if (scopes.isNotEmpty()) {
            appendLine("Repair scope:")
            scopes.forEach { scope ->
                val owners = diagnostics.filter { it.repairScope == scope }.mapNotNull { it.ownerRef }.distinct()
                appendLine("- ${scope.name.lowercase()} owners=${owners.ifEmpty { listOf("unscoped") }.joinToString(",")}")
            }
            appendLine()
        }
        appendLine("Diagnostics:")
        diagnostics.forEach { diagnostic ->
            appendLine("- ${normalizer.formatDiagnostic(diagnostic)}")
        }
    }

    private fun renderFailureContext(evaluation: BpmnEvaluation): String = buildString {
        appendLine("<render failed>")
        evaluation.renderFailureMessage?.let { appendLine(it) }
    }
}
