/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import java.util.regex.PatternSyntaxException

/**
 * Element-property regex check, optionally extended with two word-boundary vocabulary lists:
 *
 *  - [PropertyPatternCheckConfig.allowedVocabulary] — case-sensitive exemption list applied
 *    BEFORE the regex; conceptually the value is "scrubbed" of allowed tokens before [pattern]
 *    runs. Lets rules write a `"no all-caps run"` regex while specific acronyms (`BPMN`, `IT`)
 *    pass without per-acronym alternation in the pattern.
 *  - [PropertyPatternCheckConfig.forbiddenVocabulary] — case-insensitive blocklist applied
 *    INDEPENDENTLY of the regex; any hit flags the element. The forbidden check runs against
 *    the unscrubbed value so allowed-list entries cannot mask a forbidden hit by accident.
 *
 * Either list may be `null`; when both are null the primitive is identical to a pure regex
 * check and existing rules (`ActivityLabelCapitalization`, `VerbObjectName`) behave unchanged.
 */
internal class PropertyPatternCheck {
    fun evaluate(
        ctx: BpmnDefinitionContext,
        metadata: RuleMetadata,
        config: PropertyPatternCheckConfig,
    ): List<RuleDiagnostic> = evaluate(ctx.toPrimitiveModelContext(), metadata, config)

    fun evaluate(
        model: PrimitiveModelContext,
        metadata: RuleMetadata,
        config: PropertyPatternCheckConfig,
    ): List<RuleDiagnostic> {
        // Compile the pattern eagerly so a malformed regex from a rule's Pkl `pattern` field
        // surfaces as a focused rule-config-error diagnostic instead of an opaque
        // `rule-execution-failure` from the engine's outer runCatching wrapper.
        val regex = try {
            Regex(config.pattern)
        } catch (e: PatternSyntaxException) {
            return listOf(metadata.configError("pattern '${config.pattern}' is not a valid regex: ${e.description}"))
        }
        return metadata.targetedElements(model)
            .filter { element ->
                val value = element.property(config.property)
                if (value.isNullOrBlank()) return@filter false
                shouldFlag(value, regex, config)
            }
            .map { metadata.diagnostic(it.id, config.patternDescription ?: config.pattern) }
    }

    private fun shouldFlag(
        value: String,
        regex: Regex,
        config: PropertyPatternCheckConfig,
    ): Boolean {
        val scrubbed = scrubAllowed(value, config.allowedVocabulary)
        // Empty scrubbed value means every word in the original was on the allowlist — pass.
        val regexFlag = scrubbed.isNotBlank() && !regex.matches(scrubbed)
        // Forbidden check runs on the unscrubbed value — allowedVocabulary must never mask a
        // forbidden hit; the two lists carry orthogonal intent.
        val forbiddenFlag = containsAnyWord(value, config.forbiddenVocabulary)
        return regexFlag || forbiddenFlag
    }

    private fun scrubAllowed(value: String, allowed: List<String>?): String {
        if (allowed.isNullOrEmpty()) return value
        val scrubbed = allowed.fold(value) { acc, token ->
            acc.replace(Regex("\\b" + Regex.escape(token) + "\\b"), " ")
        }
        return scrubbed.replace(Regex("\\s+"), " ").trim()
    }

    private fun containsAnyWord(value: String, words: List<String>?): Boolean {
        if (words.isNullOrEmpty()) return false
        return words.any { token ->
            Regex("\\b" + Regex.escape(token) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
        }
    }
}
