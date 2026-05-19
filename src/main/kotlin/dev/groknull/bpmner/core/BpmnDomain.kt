/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

enum class GenerationMode {
    SINGLE_SHOT,
    INTERACTIVE,
}

data class BpmnRequest(
    @get:JsonPropertyDescription("Natural-language description of the workflow to model")
    val processDescription: String,
    @get:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    val styleGuide: String? = null,
    @get:JsonPropertyDescription("Optional BPMN output file path. Required for file generation mode.")
    val outputFile: String? = null,
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    @field:Valid
    @get:JsonPropertyDescription("Ordered answered clarification history for this generation request")
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
) : PromptContributor {
    override fun contribution(): String =
        buildString {
            appendLine(
                """
                You are a BPMN process design expert. Given a workflow description — business, automated,
                technical, scientific, or personal — generate a typed BPMN process definition object that
                can be converted to valid BPMN 2.0 XML.

                Rules:
                - Return a single process definition object with processId, processName, nodes, and sequences.
                - Every node id and sequence id must be unique.
                - Every sequence sourceRef and targetRef must reference an existing node id.
                - Include at least one START_EVENT and one END_EVENT.
                - Use clear, descriptive names on tasks and events that faithfully reflect the source workflow.
                - Name diverging gateways as decision questions; leave converging gateways unnamed.
                - Keep process topology coherent with no dangling references.
                - A sequence flow with `sourceRef == targetRef` is forbidden. Back-edges to earlier
                  elements (different sourceRef and targetRef where the target has already been visited)
                  are valid and required to encode iterative loops.
                - Use conditionExpression on conditional gateway branches when needed.

                If you receive validation errors, fix them and return the full corrected object.
                """.trimIndent(),
            )
            if (styleGuide != null) {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## Style guide")
                appendLine()
                appendLine(styleGuide)
            }
        }
}

@JsonClassDescription("Typed BPMN process definition describing the semantic topology of a workflow")
data class BpmnDefinition(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable BPMN process id, e.g. Process_1")
    val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable BPMN process name")
    val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("All BPMN nodes participating in the process graph")
    val nodes: List<BpmnNode>,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Directed sequence-flow edges connecting node ids")
    val sequences: List<BpmnEdge>,
)

@JsonClassDescription("BPMN node with semantic type")
data class BpmnNode(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique node id, e.g. StartEvent_1")
    val id: String,
    @get:JsonPropertyDescription(
        "Optional node label. Required for tasks, events, and diverging gateways; omit for converging gateways.",
    )
    val name: String? = null,
    @get:JsonPropertyDescription("Node type from the supported enum")
    val type: NodeType,
)

@JsonClassDescription("Directed BPMN sequence flow with optional label and condition")
data class BpmnEdge(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique sequence-flow id, e.g. Flow_1")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source node id")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target node id")
    val targetRef: String,
    @get:JsonPropertyDescription("Optional human-readable sequence-flow label")
    val name: String? = null,
    @get:JsonPropertyDescription("Optional sequence-flow condition expression, typically used on gateway branches")
    val conditionExpression: String? = null,
)

enum class NodeType {
    START_EVENT,
    USER_TASK,
    SERVICE_TASK,
    EXCLUSIVE_GATEWAY,
    END_EVENT,
}
