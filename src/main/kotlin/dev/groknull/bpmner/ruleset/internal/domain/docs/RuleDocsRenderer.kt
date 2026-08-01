/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.docs

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleMetadata

/**
 * Renders the BPMN rule catalog from [RuleMetadata] beans.
 */
internal object RuleDocsRenderer {

    private val MARKDOWN_ESCAPE_REGEX = Regex("[\\[\\]()]")

    /**
     * Renders documentation for all rules, sorted by canonical rule ID.
     */
    fun render(rules: List<BpmnRule>): String = buildString {
        append("---\n")
        append("markdownlint-disable: MD013\n")
        append("---\n\n")
        append("# BPMN Rules\n\n")
        rules.sortedBy { it.metadata.id }.forEachIndexed { index, rule ->
            if (index > 0) append("\n")
            append(renderOne(rule))
        }
    }.normalized()

    /**
     * Renders one rule's catalog section.
     */
    // Suppressed because splitting the sequential construction of Markdown sections into smaller methods
    // would hurt readability and coherence of the document template.
    @Suppress("LongMethod")
    private fun renderOne(rule: BpmnRule): String {
        val aliases = if (rule.metadata.aliases.isNotEmpty()) {
            "- **Legacy Aliases**: `${rule.metadata.aliases.joinToString("`, `")}`\n"
        } else {
            ""
        }
        val deprecation = if (rule.metadata.deprecated) {
            "- **Deprecated**: Yes\n" +
                "- **Replaced By**: `${rule.metadata.replacedBy.joinToString("`, `")}`\n" +
                "- **Deprecation Reason**: ${rule.metadata.deprecationReason}\n"
        } else {
            ""
        }
        val diagnostics = rule.metadata.errorMessages.entries.sortedBy { it.key }
            .joinToString("\n") { "- `${it.key}`: ${escapeMarkdown(it.value)}" }
        val handler = if (rule.metadata.repair.handler != null) {
            "- **Handler**: `${rule.metadata.repair.handler}`\n"
        } else {
            ""
        }
        val replacementMap = rule.metadata.repair.replacementMap
        val replacements = if (replacementMap != null && replacementMap.isNotEmpty()) {
            val entries = replacementMap.entries.sortedBy { it.key }
                .joinToString("\n") { "- `${it.key}` → `${escapeMarkdown(it.value)}`" }
            "### Replacements\n$entries\n"
        } else {
            ""
        }

        val body = buildString {
            append("- **Kind**: `${rule.metadata.repair.kind.name}`\n")
            append("- **Safety**: `${rule.metadata.repair.safety.name}`\n")
            append(handler)
            if (replacements.isNotEmpty()) {
                append("\n")
                append(replacements)
            }
        }

        return buildString {
            append("## ${rule.metadata.id}\n")
            append("\n")
            append("- **Name**: ${escapeMarkdown(rule.metadata.name)}\n")
            append("- **Category**: ${rule.metadata.category.displayName}\n")
            append("- **Severity**: ${rule.metadata.severity.name}\n")
            append("- **Target Elements**: `${rule.metadata.targetElements.joinToString("`, `")}`\n")
            append(aliases)
            append(deprecation)
            append("\n")
            append("### Purpose\n")
            append("\n")
            append("${rule.metadata.intent}\n")
            append("\n")
            append("### Modeller Guidance\n")
            append("\n")
            append("${rule.metadata.forModellers}\n")
            append("\n")
            append("### AI Guidance\n")
            append("\n")
            append("${rule.metadata.forAI}\n")
            append("\n")
            append("### Diagnostic Messages\n")
            append("\n")
            append("$diagnostics\n")
            append("\n")
            append("### Repair\n")
            append("\n")
            append(body)
        }
    }

    /**
     * Escapes special Markdown characters in text content to prevent rendering issues.
     */
    private fun escapeMarkdown(text: String): String {
        // Escape special characters that could break markdown formatting
        return text.replace(MARKDOWN_ESCAPE_REGEX, "\\\\$0")
    }

    private fun String.normalized(): String = lines()
        .map { it.trimEnd() }
        .dropLastWhile { it.isEmpty() }
        .joinToString("\n") + "\n"
}
