/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.generationPrompt
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
    ): List<Message> = listOf(
        UserMessage(request.generationPrompt()),
        AssistantMessage(fingerprints.serializeDefinition(definition)),
    )

    override fun patchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): String = buildString {
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
        appendDiagnosticBlock(diagnostics)
    }

    override fun fullRepairFeedback(
        attempt: BpmnRepairAttempt,
        diagnostics: List<BpmnDiagnostic>,
    ): String = fullRepairFeedback(
        definition = attempt.definition,
        renderedXml = attempt.evaluation.rendered?.xml ?: renderFailureContext(attempt.evaluation),
        diagnostics = diagnostics,
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
    ): String = buildString {
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
        appendLine(
            "Preserve `isDefault = true` on sequence flows that carry the default branch marker. " +
                "Do not add a conditionExpression to a default flow. The `default` attribute on an " +
                "exclusive gateway is set by exactly one outbound flow with isDefault=true.",
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
        appendDiagnosticBlock(diagnostics)
    }

    /**
     * Render the diagnostic list grouped by severity (errors first), with a single sentence
     * teaching the LLM the contract: ERRORs must be fixed; WARNINGs are advisory. Without
     * this guidance the repair LLM treats warnings as forcing functions and may invent
     * structural changes to satisfy them — which is exactly the failure mode that produced
     * the credit-tier run's conditional-flow regressions.
     *
     * Phase 4 (#219) dropped the local-fix-failure suffix because GOAP's cost ordering +
     * fingerprint cycle delivers the escalation that the prior `failedLocally` flag tracked
     * manually — the LLM no longer needs the per-diagnostic local-outcome context.
     */
    private fun StringBuilder.appendDiagnosticBlock(diagnostics: List<BpmnDiagnostic>) {
        val errors = diagnostics.filter { it.isBlocking }
        val advisories = diagnostics.filterNot { it.isBlocking }
        if (errors.isNotEmpty()) {
            appendLine("ERRORs — MUST be fixed; the pipeline cannot succeed while any remain:")
            errors.forEach { d -> appendLine(formatDiagnostic(d)) }
        }
        if (advisories.isNotEmpty()) {
            if (errors.isNotEmpty()) appendLine()
            appendLine(
                "WARNINGs / INFO — advisory only. Fix if the change is a clear, local rename" +
                    " or label tweak; NEVER invent or restructure flows to satisfy a warning.",
            )
            advisories.forEach { d -> appendLine(formatDiagnostic(d)) }
        }
    }

    private fun formatDiagnostic(diagnostic: BpmnDiagnostic): String {
        val base = "- ${diagnostic.format()}"
        // For known naming-rule violations, append a kind-specific shape recommendation so
        // the LLM has concrete examples of compliant names — addresses the failure mode where
        // the repair LLM produces a rename that still violates the rule's wink-NLP detector.
        val shapeHintSuffix =
            diagnostic.rule
                ?.let { BpmnNamingShapeAdvice.adviceForRule(it) }
                ?.let { advice ->
                    "\n      hint (${advice.kind}): ${advice.shape}" +
                        " examples=${advice.examples.joinToString(", ") { "\"$it\"" }}" +
                        " avoid=${advice.antiExamples.joinToString(", ") { "\"$it\"" }}"
                }.orEmpty()
        return base + shapeHintSuffix
    }

    private fun renderFailureContext(evaluation: BpmnEvaluation): String = buildString {
        appendLine("<render failed>")
        evaluation.renderFailureMessage?.let { appendLine(it) }
    }
}
