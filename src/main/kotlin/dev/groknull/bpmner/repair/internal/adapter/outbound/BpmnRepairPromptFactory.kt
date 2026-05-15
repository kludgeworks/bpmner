package dev.groknull.bpmner.repair.internal.adapter.outbound

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.generationPrompt
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPromptPort
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRuleGuidancePort
import dev.groknull.bpmner.validation.format
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class BpmnRepairPromptFactory(
    private val bpmnLintingPort: BpmnLintingPort,
    private val fingerprints: BpmnFingerprintService,
    private val ruleGuidance: BpmnRuleGuidancePort,
) : BpmnRepairPromptPort {
    override fun initialMessages(
        request: BpmnRequest,
        definition: BpmnDefinition,
    ): List<Message> =
        listOf(
            UserMessage(request.generationPrompt()),
            AssistantMessage(fingerprints.serializeDefinition(definition)),
        )

    override fun patchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
        localOutcome: BpmnLocalRepairOutcome,
    ): String =
        buildString {
            appendLine("The following diagnostics can be fixed with targeted name or label patches.")
            appendLine("Return a BpmnRepairPatch with the minimum operations needed to fix these issues.")
            appendLine(
                "Do not rewrite the whole graph — only include operations that " +
                    "directly address the listed diagnostics.",
            )
            appendLine()
            val guidance = ruleGuidance.getLlmRuleGuidance()
            if (guidance.isNotEmpty()) {
                appendLine(guidance)
                appendLine()
            }
            appendLine("Current canonical BpmnDefinition JSON:")
            appendLine(fingerprints.serializeDefinition(definition))
            appendLine()
            appendLine("Diagnostics to fix:")
            diagnostics.forEach { d -> appendLine(formatDiagnosticWithLocalContext(d, localOutcome)) }
        }

    override fun fullRepairFeedback(
        attempt: BpmnRepairAttempt,
        diagnostics: List<BpmnDiagnostic>,
        localOutcome: BpmnLocalRepairOutcome,
    ): String =
        fullRepairFeedback(
            definition = attempt.definition,
            renderedXml = attempt.evaluation.rendered?.xml ?: renderFailureContext(attempt.evaluation),
            diagnostics = diagnostics,
            localOutcome = localOutcome,
        )

    override fun lintRuleDocsPrompt(diagnostics: List<BpmnDiagnostic>): PromptContributor? {
        val lintRules =
            diagnostics
                .asSequence()
                .filter { it.source == BpmnDiagnosticSource.LINT }
                .mapNotNull { it.rule }
                .distinct()
                .toList()
        if (lintRules.isEmpty()) return null

        val docs = bpmnLintingPort.ruleDocs(lintRules)
        if (docs.isEmpty()) return null

        val content =
            buildString {
                appendLine("BPMN lint rule documentation for current violations:")
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
        localOutcome: BpmnLocalRepairOutcome,
    ): String =
        buildString {
            appendLine("The BPMN definition needs repair. Return the full corrected BpmnDefinition object.")
            appendLine()
            val guidance = ruleGuidance.getLlmRuleGuidance()
            if (guidance.isNotEmpty()) {
                appendLine(guidance)
                appendLine()
            }
            appendLine("Use the typed BPMN definition as the canonical edit surface.")
            appendLine(
                "Use the rendered BPMN XML only as supporting context when diagnostics refer to rendered elements.",
            )
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
                    appendLine(
                        "- ${scope.name.lowercase()} owners=" +
                            owners.ifEmpty { listOf("unscoped") }.joinToString(","),
                    )
                }
                appendLine()
            }
            appendLine("Diagnostics:")
            diagnostics.forEach { d -> appendLine(formatDiagnosticWithLocalContext(d, localOutcome)) }
        }

    private fun formatDiagnosticWithLocalContext(
        diagnostic: BpmnDiagnostic,
        localOutcome: BpmnLocalRepairOutcome,
    ): String {
        val base = "- ${diagnostic.format()}"
        val failure = localOutcome.matches(diagnostic) ?: return base
        return base + " [local-fix-failed: ${failure.reason}]"
    }

    private fun renderFailureContext(evaluation: BpmnEvaluation): String =
        buildString {
            appendLine("<render failed>")
            evaluation.renderFailureMessage?.let { appendLine(it) }
        }
}
