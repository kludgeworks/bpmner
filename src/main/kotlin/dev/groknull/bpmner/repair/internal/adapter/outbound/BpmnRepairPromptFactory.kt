/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.textio.template.TemplateRenderer
import dev.groknull.bpmner.authoring.generationPrompt
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.conformance.BpmnLintingPort
import dev.groknull.bpmner.conformance.BpmnRuleGuidancePort
import dev.groknull.bpmner.conformance.format
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPromptPort
import dev.groknull.bpmner.repair.internal.domain.BpmnUnrecognizedElementScanner
import dev.groknull.bpmner.ruleset.BpmnNamingShapeAdvice
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
internal class BpmnRepairPromptFactory(
    private val bpmnLintingPort: BpmnLintingPort,
    private val fingerprints: BpmnFingerprintService,
    private val ruleGuidance: BpmnRuleGuidancePort,
    private val templateRenderer: TemplateRenderer,
) : BpmnRepairPromptPort {
    override fun initialMessages(
        request: BpmnRequest,
        definition: BpmnDefinition,
    ): List<Message> {
        requireRecognized(definition)
        return listOf(
            UserMessage(request.generationPrompt()),
            AssistantMessage(fingerprints.serializeDefinition(definition)),
        )
    }

    override fun patchFeedback(
        definition: BpmnDefinition,
        diagnostics: List<BpmnDiagnostic>,
    ): String {
        requireRecognized(definition)
        return templateRenderer.renderLoadedTemplate(
            "bpmner/repair/patch_feedback",
            mapOf(
                "guidance" to ruleGuidance.getLlmRuleGuidance(),
                "canonicalJson" to fingerprints.serializeDefinition(definition),
                "diagnosticBlock" to diagnosticBlock(diagnostics),
            ),
        )
    }

    override fun fullRepairFeedback(
        attempt: BpmnRepairAttempt,
        diagnostics: List<BpmnDiagnostic>,
    ): String {
        requireRecognized(attempt.definition)
        return templateRenderer.renderLoadedTemplate(
            "bpmner/repair/full_feedback",
            mapOf(
                "guidance" to ruleGuidance.getLlmRuleGuidance(),
                "canonicalJson" to fingerprints.serializeDefinition(attempt.definition),
                "renderedXml" to (attempt.evaluation.rendered?.xml ?: renderFailureContext(attempt.evaluation)),
                "scopeBlock" to scopeBlock(diagnostics),
                "diagnosticBlock" to diagnosticBlock(diagnostics),
            ),
        )
    }

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

    /**
     * Render the diagnostic list grouped by severity (errors first). The contract teaches
     * the LLM that ERRORs must be fixed and WARNINGs are advisory — without this guidance
     * the repair LLM may invent structural changes to satisfy warnings.
     */
    private fun diagnosticBlock(diagnostics: List<BpmnDiagnostic>): String = buildString {
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
    }.trimEnd()

    private fun scopeBlock(diagnostics: List<BpmnDiagnostic>): String {
        val scopes = diagnostics.mapNotNull { it.repairScope }.distinct()
        if (scopes.isEmpty()) return ""
        return buildString {
            appendLine("Repair scope:")
            scopes.forEach { scope ->
                val owners = diagnostics.filter { it.repairScope == scope }.mapNotNull { it.ownerRef }.distinct()
                appendLine(
                    "- ${scope.name.lowercase()} owners=" +
                        owners.ifEmpty { listOf("unscoped") }.joinToString(","),
                )
            }
        }.trimEnd()
    }

    private fun formatDiagnostic(diagnostic: BpmnDiagnostic): String {
        val base = "- ${diagnostic.format()}"
        // Append a kind-specific shape recommendation for naming-rule violations so the LLM
        // has concrete examples of compliant names. Addresses repair LLM producing renames
        // that still violate the rule's wink-NLP detector.
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

    /**
     * Belt-and-braces guard for the unrecognized-element scanner. Pre-flight in
     * BpmnRepairAgent.validate short-circuits before these prompt-building methods run;
     * if that pre-flight is ever bypassed, fail here with a named precondition rather than
     * propagating an opaque Jackson InvalidDefinitionException from
     * fingerprints.serializeDefinition.
     */
    private fun requireRecognized(definition: BpmnDefinition) {
        check(BpmnUnrecognizedElementScanner.scan(definition).isEmpty()) {
            "BpmnRepairPromptFactory invoked on definition containing unrecognized elements; " +
                "pre-flight in BpmnRepairAgent.validate should have rejected this."
        }
    }
}
