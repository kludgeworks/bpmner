/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.constraints.Size

@JsonClassDescription("Draft BPMN generation request extracted from natural-language shell input")
data class BpmnRequestDraft(
    @field:Size(max = MAX_PROCESS_DESCRIPTION_LENGTH)
    @get:JsonPropertyDescription(
        "Inline process prose supplied by the user. Set this when the user described the workflow directly.",
    )
    val processDescription: String? = null,
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
) {
    companion object {
        const val MAX_PROCESS_DESCRIPTION_LENGTH = 10_000
        const val MAX_STYLE_GUIDE_LENGTH = 20_000
    }
}
