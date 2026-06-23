/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.authoring.internal.adapter.inbound.InputPathResolver
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
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
) : BpmnRequestResolutionPort {
    override fun resolveShellRequest(draft: BpmnRequestDraft): BpmnRequest {
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
        val inline = draft.processDescription?.trim()?.takeIf { it.isNotEmpty() }
        val file = draft.processFile?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            inline != null -> {
                require(file == null) { "Provide exactly one process description, either inline prose or a process file." }
                inline
            }
            file != null -> {
                inputPathResolver.readUtf8(file).trim()
            }
            else -> {
                throw IllegalArgumentException("Provide exactly one process description, either inline prose or a process file.")
            }
        }
    }

    private fun resolveStyleGuide(draft: BpmnRequestDraft): String? {
        val inline = draft.styleGuide?.trim()?.takeIf { it.isNotEmpty() }
        val file = draft.styleGuideFile?.trim()?.takeIf { it.isNotEmpty() }
        require(inline == null || file == null) {
            "Provide at most one style guide, either inline text or a style-guide file."
        }
        return when {
            inline != null -> inline
            file != null -> inputPathResolver.readUtf8(file).trim()
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val DEFAULT_SHELL_OUTPUT = "output.bpmn"
    }
}
