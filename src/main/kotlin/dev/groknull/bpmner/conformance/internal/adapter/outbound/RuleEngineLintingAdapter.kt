/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.conformance.BpmnAutoFixResult
import dev.groknull.bpmner.conformance.BpmnLintRuleCapability
import dev.groknull.bpmner.conformance.BpmnLintRuleIds
import dev.groknull.bpmner.conformance.BpmnLintingPort
import dev.groknull.bpmner.conformance.LintIssue
import dev.groknull.bpmner.ruleset.RuleEngine
import dev.groknull.bpmner.ruleset.RuleRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Backs [BpmnLintingPort] with the Pkl rule catalog plus the [RuleEngine]. Replaces the
 * legacy GraalJS-hosted bpmn-lint bridge (`BpmnLintService` and its JS context) so the
 * `bpmnlint` TS codebase, `linter-rules.json`, `Catalog.pkl`, and `bpmner.lint.*` runtime
 * config can be retired. GraalJS now only lives inside [..layout..].
 *
 * The port surface is preserved so existing consumers — `BpmnEvaluationPipeline`,
 * `BpmnDiagnosticNormalizer`, `BpmnLocalRepairCapabilityValidator`, `BpmnRepairPromptFactory`
 * — keep working unchanged. The interface itself (and `LintIssue` / `BpmnLintRuleCapability`
 * shapes) will be renamed in a follow-up; this commit is a Branch-by-Abstraction swap of
 * the implementation only.
 *
 * [autoFix] returns `null`: the JS auto-fix path is gone. Safe-to-execute deterministic
 * repairs flow through Kotlin handlers under `DeterministicTopologyRepairStrategy` instead
 * (`LOCAL_MODEL_FIX` kind).
 */
// NOTE: @SecondaryAdapter is deliberately absent (ADR-23 Decision 2). This adapter calls
// RuleEngine and RuleRegistry, both @PrimaryPort interfaces in rules/, which violates the
// jMolecules ensureHexagonal(LENIENT) rule for the @SecondaryAdapter layer. This adapter is
// an Anti-Corruption Layer (ACL) over rules' driving surface, not a pure secondary adapter.
// The ACL shape is enforced by a BpmnerArchitectureTest pin: RuleEngineLintingAdapter is the
// sole validation class permitted to depend on rules' @PrimaryPorts.
@Component
internal class RuleEngineLintingAdapter(
    private val ruleEngine: RuleEngine,
    private val ruleRegistry: RuleRegistry,
) : BpmnLintingPort {
    private val logger = LoggerFactory.getLogger(RuleEngineLintingAdapter::class.java)

    private val capabilities: Map<String, BpmnLintRuleCapability> by lazy {
        buildMap {
            for (rule in ruleRegistry.activeRules()) {
                val capability = rule.toCapability()
                put(rule.id, capability)
                rule.metadata.aliases.forEach { alias -> put(alias, capability) }
            }
        }.also {
            logger.info("Loaded {} rule capabilities from rule registry", it.size)
        }
    }

    override fun lint(definition: BpmnDefinition): List<LintIssue> {
        val diagnostics = ruleEngine.evaluate(definition).diagnostics
        return diagnostics.map { d ->
            LintIssue(
                id = d.elementId,
                rule = d.ruleId,
                message = d.message,
                category = d.severity.toLintCategory(),
            )
        }
    }

    override fun autoFix(
        bpmnXml: String,
        issues: List<LintIssue>,
    ): BpmnAutoFixResult? = null

    override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> = ruleNames
        .distinct()
        .mapNotNull { name ->
            // Strip any legacy `bpmner/` or `bpmnlint-plugin-bpmner/` prefix before lookup —
            // `RuleRegistry` indexes by bare id. Defensive against externally-sourced
            // diagnostics whose `rule` field still carries the prefix.
            val bareName = BpmnLintRuleIds.bareRuleId(name)
            ruleRegistry.ruleByIdOrAlias(bareName)?.metadata?.let { name to renderRuleMarkdown(it) }
        }
        .toMap()

    override fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability> = capabilities

    private fun BpmnRule.toCapability(): BpmnLintRuleCapability {
        val repair = metadata.repair
        return BpmnLintRuleCapability(
            id = id,
            kind = repair.kind,
            repairSafety = repair.safety,
            fixHandler = repair.handler,
            handlerExists = repair.kind.isLocal(),
            replacementMap = repair.replacementMap,
        )
    }

    private fun RuleSeverity.toLintCategory(): String = when (this) {
        RuleSeverity.ERROR -> "error"
        RuleSeverity.WARNING -> "warn"
        RuleSeverity.INFO -> "info"
    }

    private fun renderRuleMarkdown(metadata: RuleMetadata): String = buildString {
        appendLine("# ${metadata.name}")
        appendLine()
        if (metadata.intent.isNotBlank()) {
            appendLine(metadata.intent.trim())
            appendLine()
        }
        if (metadata.forModellers.isNotBlank()) {
            appendLine("**For modellers:**")
            appendLine(metadata.forModellers.trim())
            appendLine()
        }
        if (metadata.forAI.isNotBlank()) {
            appendLine("**For AI:**")
            appendLine(metadata.forAI.trim())
            appendLine()
        }
        if (metadata.errorMessages.isNotEmpty()) {
            appendLine("**Error messages:**")
            metadata.errorMessages.forEach { (code, msg) ->
                appendLine("- `$code`: ${msg.trim()}")
            }
        }
    }.trim()
}
