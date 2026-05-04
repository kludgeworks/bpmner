package dev.groknull.bpmner.agent

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Input to the BPMN generation agent.
 */
data class BpmnRequest(
    @param:JsonPropertyDescription("Natural-language description of the business process to model")
    val processDescription: String,
    @param:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    val styleGuide: String? = null,
    val outputFile: String = "output.bpmn",
)

/**
 * BPMN XML that has passed both XSD and bpmn-lint validation.
 */
data class ValidatedBpmnXml(val xml: String)

/**
 * Final result written to disk.
 */
data class BpmnResult(
    val outputFile: String,
    val xml: String,
)
