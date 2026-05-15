package dev.groknull.bpmner.generation

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import jakarta.validation.Valid

enum class BpmnGenerationStatus {
    GENERATED,
    NEEDS_CLARIFICATION,
    NOT_A_PROCESS,
    ALIGNMENT_FAILED,
    VALIDATION_FAILED,
}

fun BpmnRequest.generationPrompt(): String =
    buildString {
        appendLine("Generate a BPMN definition object for this business process.")
        appendLine()
        appendLine("Business process description:")
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
)
