/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
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
        // Compile the vocabulary regexes once per evaluate call. Each list collapses into a single
        // alternation (`\b(?:word1|word2)\b`) so element scanning is O(elements) Regex calls
        // regardless of vocabulary size.
        val allowedRegex = alternationRegex(config.allowedVocabulary, ignoreCase = false)
        val forbiddenRegex = alternationRegex(config.forbiddenVocabulary, ignoreCase = true)

        return metadata.targetedElements(model)
            .mapNotNull { element ->
                val value = element.property(config.property)
                if (value.isNullOrBlank()) return@mapNotNull null
                val reason = classify(value, regex, allowedRegex, forbiddenRegex) ?: return@mapNotNull null
                metadata.diagnostic(element.id, messageSuffix(reason, config))
            }
    }

    private fun classify(value: String, regex: Regex, allowedRegex: Regex?, forbiddenRegex: Regex?): HitReason? {
        val scrubbed = scrubAllowed(value, allowedRegex)
        // Empty scrubbed value means every word in the original was on the allowlist — pass.
        val regexFails = scrubbed.isNotBlank() && !regex.matches(scrubbed)
        // Forbidden check runs on the unscrubbed value — allowedVocabulary must never mask a
        // forbidden hit; the two lists carry orthogonal intent.
        val forbiddenHit = forbiddenRegex?.find(value)?.value
        return when {
            regexFails && forbiddenHit != null -> HitReason.Both(forbiddenHit)
            regexFails -> HitReason.PatternMiss
            forbiddenHit != null -> HitReason.ForbiddenToken(forbiddenHit)
            else -> null
        }
    }

    private fun messageSuffix(reason: HitReason, config: PropertyPatternCheckConfig): String {
        val patternIntent = config.patternDescription ?: config.pattern
        return when (reason) {
            HitReason.PatternMiss -> patternIntent
            is HitReason.ForbiddenToken -> "contains forbidden token '${reason.token}'"
            is HitReason.Both -> "$patternIntent; also contains forbidden token '${reason.token}'"
        }
    }

    private fun scrubAllowed(value: String, allowedRegex: Regex?): String {
        if (allowedRegex == null) return value
        return WHITESPACE.replace(value.replace(allowedRegex, " "), " ").trim()
    }

    private sealed interface HitReason {
        data object PatternMiss : HitReason
        data class ForbiddenToken(val token: String) : HitReason
        data class Both(val token: String) : HitReason
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        private fun alternationRegex(tokens: List<String>?, ignoreCase: Boolean): Regex? {
            if (tokens.isNullOrEmpty()) return null
            val body = tokens.joinToString("|") { Regex.escape(it) }
            return if (ignoreCase) {
                Regex("\\b(?:$body)\\b", RegexOption.IGNORE_CASE)
            } else {
                Regex("\\b(?:$body)\\b")
            }
        }
    }
}
