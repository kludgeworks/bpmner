/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.embabel.agent.domain.library.HasContent
import com.embabel.common.core.types.HasInfoString
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import jakarta.validation.Valid
import java.nio.file.Path

enum class BpmnGenerationStatus {
    GENERATED,
    NEEDS_CLARIFICATION,
    ALIGNMENT_FAILED,
    VALIDATION_FAILED,
}

fun BpmnRequest.generationPrompt(): String = buildString {
    appendLine("Generate a BPMN definition object for this workflow.")
    appendLine()
    appendLine("Workflow description:")
    appendLine(processDescription)
    if (clarificationHistory.isNotEmpty()) {
        appendLine()
        appendLine("Clarification answers:")
        clarificationHistory.forEach {
            appendLine("- [${it.questionId}] ${it.questionText}")
            appendLine("  Answer: ${it.answerText}")
        }
    }
}

@JsonClassDescription("Result of a BPMN generation request")
data class BpmnResult(
    @get:JsonPropertyDescription("Requested BPMN output file path, if any")
    val outputFile: String?,
    @get:JsonPropertyDescription("Generation status")
    val status: BpmnGenerationStatus,
    @get:JsonPropertyDescription("Generated BPMN XML when generation succeeds")
    val xml: String? = null,
    @field:Valid
    @get:JsonPropertyDescription("Readiness report when generation needs clarification or is blocked")
    val readinessReport: ProcessInputAssessment? = null,
    @field:Valid
    @get:JsonPropertyDescription("Alignment report when alignment has been evaluated")
    val alignmentReport: BpmnAlignmentReport? = null,
    @get:JsonPropertyDescription("Optional report output file path for guardrail diagnostics")
    val reportFile: String? = null,
) : HasInfoString,
    HasContent {
    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val destination = outputFile ?: "(none)"
        val base = "BPMN status=$status, output=$destination"
        val details =
            when {
                status == BpmnGenerationStatus.GENERATED -> ", xmlLength=${xml?.length ?: 0}"
                reportFile != null -> ", report=$reportFile"
                else -> ""
            }
        return "\t".repeat(indent) + base + details
    }

    // Concise human-facing summary the shell renders on completion (embabel's formatProcessOutput
    // prints HasContent.content; without it the whole result — including the full XML — is dumped as
    // JSON). Only the file name is shown, not its path. @JsonIgnore keeps this computed field out of
    // the web JSON contract, which also returns BpmnResult.
    @get:JsonIgnore
    override val content: String
        get() = when (status) {
            BpmnGenerationStatus.GENERATED ->
                "$GENERATED_CONTENT_PREFIX${outputFileName(outputFile)} (${xml?.length ?: 0} chars)."

            BpmnGenerationStatus.NEEDS_CLARIFICATION ->
                "Not ready to generate: the input needs clarification." +
                    (reportFile?.let { " Readiness report: ${outputFileName(it)}" } ?: "")

            else ->
                "BPMN generation did not complete (status=$status)." +
                    (reportFile?.let { " Report: ${outputFileName(it)}" } ?: "")
        }
}

// Prefix of the GENERATED [BpmnResult.content] line. Shared so the shell command can recover the
// file name from embabel's formatted result and echo it as the final line.
internal const val GENERATED_CONTENT_PREFIX = "Generated BPMN → "

/** The file name (last path segment) of [outputFile], or a placeholder when nothing was written. */
internal fun outputFileName(outputFile: String?): String {
    if (outputFile.isNullOrBlank()) return "(not written to a file)"
    return Path.of(outputFile).fileName?.toString() ?: outputFile
}
