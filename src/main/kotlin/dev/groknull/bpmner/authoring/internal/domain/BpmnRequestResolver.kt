/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.authoring.BpmnRequestResolutionPort
import dev.groknull.bpmner.authoring.internal.adapter.inbound.InputPathResolver
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import org.springframework.stereotype.Component

/**
 * Resolves a shell [BpmnRequestDraft] (inline prose or file paths) into a fully resolved
 * [BpmnRequest] suitable for the generation pipeline.
 *
 * Relocated from `authoring` root package to `authoring.internal.domain` as part of S9
 * (ADR-009 (port-fronting) disposition a). Cross-module callers inject [BpmnRequestResolutionPort] instead.
 * The relocation lets Modulith's `verify()` (mechanism 1) enforce the `*.internal.*` package
 * path and reject any future cross-module direct reach.
 *
 * Constructor-injects [InputPathResolver], an `authoring.internal.*` type, which is why this
 * bean belongs in `authoring.internal.domain` rather than at the module root.
 */
@Component
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
