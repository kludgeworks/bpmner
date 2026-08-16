/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Pure kernel extension for style-guide contribution text.
 *
 * Returns the style-guide header string for inclusion in a prompt contribution,
 * or an empty string when no style guide is present. This is a pure `String` function;
 * it carries no `com.embabel.*` import. Slices that drive LLM prompts wrap it locally
 * with `PromptContributor.fixed(request.styleGuideContribution())` (ADR-005 Decision 1).
 */
fun BpmnRequest.styleGuideContribution(): String = styleGuide?.let { "## Style guide\n\n$it" } ?: ""

fun BpmnRequest.targetLanguageContribution(): String =
    targetLanguage?.takeIf { it.isNotBlank() }?.let {
        "## Target language\n\nAll node names, process names, labels, annotations, and " +
            "descriptions in the generated BPMN must be written in the target language: " +
            "\"$it\" (ISO 639 2-letter code), regardless of the input language."
    } ?: ""

fun BpmnRequest.promptContributions(): String = buildString {
    val style = styleGuideContribution()
    if (style.isNotEmpty()) {
        append(style)
    }
    val lang = targetLanguageContribution()
    if (lang.isNotEmpty()) {
        if (isNotEmpty()) append("\n\n")
        append(lang)
    }
}
