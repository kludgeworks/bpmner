/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.constraints.Size

/**
 * The routing decision extracted from a shell instruction: where the process prose lives and where
 * the output goes. It deliberately carries no process prose of its own — when the user describes a
 * workflow inline, the prose is read straight from the user's input, never round-tripped through
 * this type.
 */
@JsonClassDescription("Routing decision for a BPMN generation request, extracted from shell input")
data class BpmnRequestDraft(
    @get:JsonPropertyDescription(
        "Path to a file containing process prose. Set this only when the user explicitly references a process file.",
    )
    val processFile: String? = null,
    @get:JsonPropertyDescription("Optional BPMN output file path requested by the user")
    val outputFile: String? = null,
    @field:Size(max = MAX_STYLE_GUIDE_LENGTH)
    @get:JsonPropertyDescription("Optional inline style guide text supplied by the user")
    val styleGuide: String? = null,
    @get:JsonPropertyDescription("Optional path to a file containing style-guide Markdown")
    val styleGuideFile: String? = null,
    @get:JsonPropertyDescription("Optional target language for the generated diagram (2-letter ISO 639 code)")
    val targetLanguage: String? = null,
) {
    companion object {
        const val MAX_STYLE_GUIDE_LENGTH = 20_000
    }
}
