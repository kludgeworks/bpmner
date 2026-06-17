/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.docs

import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.rules.LlmRuleSpec

/**
 * Renders BPMN rule documentation in Markdown format from [RuleMetadata] beans.
 *
 * The renderer is a **pure function** `List<BpmnRule> -> Map<String, String>` returning
 * `"<id>.md" -> markdown` and `"README.md" -> index`. It reproduces the field set and
 * ordering of the original Pkl-based docs generator template exactly.
 *
 * No Spring imports, no agent/LLM boot, no network calls — fully deterministic output
 * (sorted by rule id, stable map iteration).
 */
internal object RuleDocsRenderer {

    private val MARKDOWN_ESCAPE_REGEX = Regex("[\\[\\]()]")

    /**
     * Renders documentation for all rules.
     *
     * @param rules List of rules to render (supports both [BpmnRule] and [LlmRuleSpec]).
     * @return Map of filename to markdown content, containing one `"<id>.md"` per rule
     *   plus a `"README.md"` index.
     */
    fun render(rules: List<BpmnRule>): Map<String, String> {
        val sortedRules = rules.sortedBy { it.id }
        val files = sortedRules.associate { "${it.metadata.id}.md" to renderOne(it) }
        val index = renderIndex(sortedRules)
        return files + ("README.md" to index)
    }

    /**
     * Renders documentation for a single rule.
     *
     * @param rule The rule to render (supports both [BpmnRule] and [LlmRuleSpec]).
     * @return Markdown content for the rule's `"<id>.md"` file.
     */
    // Suppressed because splitting the sequential construction of Markdown sections into smaller methods
    // would hurt readability and coherence of the document template.
    @Suppress("LongMethod")
    private fun renderOne(rule: BpmnRule): String {
        val cookbookCode = getCookbookCode(rule)
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
            "\n### Replacements\n$entries"
        } else {
            ""
        }

        val body = buildString {
            append("- **Kind**: `${rule.metadata.repair.kind.name}`\n")
            append("- **Safety**: `${rule.metadata.repair.safety.name}`\n")
            append(handler)
            append(replacements)
            if (handler.isNotEmpty() || replacements.isNotEmpty()) {
                append("\n")
            }
        }

        val yamlFrontMatter = buildString {
            append("---\n")
            append("markdownlint-disable: MD013\n")
            append("---\n")
            append("\n")
            append("# ${rule.metadata.id}\n")
            append("\n")
            append("- **Name**: ${escapeMarkdown(rule.metadata.name)}\n")
            append("- **Category**: ${rule.metadata.category.displayName}\n")
            append("- **Severity**: ${rule.metadata.severity.name}\n")
            append("- **Target Elements**: `${rule.metadata.targetElements.joinToString("`, `")}`\n")
            append(cookbookCodeLine(cookbookCode))
            append(aliases)
            append(deprecation)
            append("\n")
            append("## Intent\n")
            append("\n")
            append("${rule.metadata.intent}\n")
            append("\n")
            append("## Modeller Guidance\n")
            append("\n")
            append("${rule.metadata.forModellers}\n")
            append("\n")
            append("## AI Guidance\n")
            append("\n")
            append("${rule.metadata.forAI}\n")
            append("\n")
            append("## Diagnostic Messages\n")
            append("\n")
            append("$diagnostics\n")
            append("\n")
            append("## Repair\n")
            append("\n")
            append(body)
        }

        return yamlFrontMatter.lines()
            .map { it.trimEnd() }
            .dropWhileLast { it.isEmpty() }
            .joinToString("\n") + "\n"
    }

    /**
     * Renders the index page.
     *
     * @param rules List of rules sorted by id.
     * @return Markdown content for `README.md`.
     */
    private fun renderIndex(rules: List<BpmnRule>): String {
        val entries = rules.joinToString("\n") {
            "- [${it.metadata.id}](${it.metadata.id}.md): ${escapeMarkdown(it.metadata.name)}"
        }
        val yamlFrontMatter = buildString {
            append("---\n")
            append("markdownlint-disable: MD013, MD032\n")
            append("---\n")
            append("\n")
            append("# Plugin Rule Documentation\n")
            append("\n")
            append("This folder documents each custom rule implemented by `bpmnlint-plugin-bpmner`.\n")
            append("\n")
            append("## Rules\n")
            append("\n")
            append(entries)
        }

        return yamlFrontMatter.lines()
            .map { it.trimEnd() }
            .dropWhileLast { it.isEmpty() }
            .joinToString("\n") + "\n"
    }

    /**
     * Extracts the cookbook code from a rule.
     *
     * Reads from `LlmRuleSpec.staticConfig` when available (LLM rules), otherwise from
     * `RuleMetadata.staticConfig` as a fallback. This supports both configuration styles.
     */
    private fun getCookbookCode(rule: BpmnRule): String? {
        return when (rule) {
            is LlmRuleSpec -> rule.staticConfig["cookbookCode"] as? String
            else -> rule.metadata.staticConfig?.get("cookbookCode") as? String
        }
    }

    private fun cookbookCodeLine(cookbookCode: String?): String {
        return if (cookbookCode != null) "- **Cookbook Code**: `$cookbookCode`\n" else ""
    }

    /**
     * Escapes special Markdown characters in text content to prevent rendering issues.
     */
    private fun escapeMarkdown(text: String): String {
        // Escape special characters that could break markdown formatting
        return text.replace(MARKDOWN_ESCAPE_REGEX, "\\\\$0")
    }

    /**
     * Removes trailing empty lines from a list.
     * Equivalent to reversed.dropWhile { it.isEmpty() }.reversed()
     */
    private fun <T> List<T>.dropWhileLast(predicate: (T) -> Boolean): List<T> {
        val lastIndex = lastIndex
        var i = lastIndex
        while (i >= 0 && predicate(this[i])) {
            i--
        }
        return subList(0, i + 1)
    }
}
