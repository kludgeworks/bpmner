/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.api.GenerationMode
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

@org.springframework.stereotype.Component
internal class BpmnRequestResolver(
    private val inputPathResolver: InputPathResolver,
) {
    fun resolveShellRequest(draft: BpmnRequestDraft): BpmnRequest {
        val description = resolveProcessDescription(draft)
        val outputFile = inputPathResolver.resolveOutputPath(draft.outputFile ?: DEFAULT_SHELL_OUTPUT).toString()
        val styleGuide = resolveStyleGuide(draft)

        return BpmnRequest(
            processDescription = description,
            styleGuide = styleGuide,
            outputFile = outputFile,
            mode = GenerationMode.INTERACTIVE,
        )
    }

    private fun resolveProcessDescription(draft: BpmnRequestDraft): String {
        val hasInlineDescription = !draft.processDescription.isNullOrBlank()
        val hasProcessFile = !draft.processFile.isNullOrBlank()
        require(hasInlineDescription != hasProcessFile) {
            "Provide exactly one process description, either inline prose or a process file."
        }

        return if (hasInlineDescription) {
            draft.processDescription!!.trim()
        } else {
            inputPathResolver.readUtf8(draft.processFile!!).trim()
        }
    }

    private fun resolveStyleGuide(draft: BpmnRequestDraft): String? {
        val hasInlineStyleGuide = !draft.styleGuide.isNullOrBlank()
        val hasStyleGuideFile = !draft.styleGuideFile.isNullOrBlank()
        require(!(hasInlineStyleGuide && hasStyleGuideFile)) {
            "Provide at most one style guide, either inline text or a style-guide file."
        }

        return when {
            hasInlineStyleGuide -> draft.styleGuide!!.trim()
            hasStyleGuideFile -> inputPathResolver.readUtf8(draft.styleGuideFile!!).trim()
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val DEFAULT_SHELL_OUTPUT = "output.bpmn"
    }
}
