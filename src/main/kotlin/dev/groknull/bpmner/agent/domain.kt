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

data class BpmnDefinition(
    @param:JsonPropertyDescription("Stable BPMN process id, e.g. Process_1")
    val processId: String,
    @param:JsonPropertyDescription("Human-readable BPMN process name")
    val processName: String,
    @param:JsonPropertyDescription("All BPMN nodes participating in the process graph")
    val nodes: List<BpmnNode>,
    @param:JsonPropertyDescription("Directed sequence-flow edges connecting node ids")
    val sequences: List<BpmnEdge>,
)

data class BpmnNode(
    @param:JsonPropertyDescription("Unique node id, e.g. StartEvent_1")
    val id: String,
    @param:JsonPropertyDescription("Node label shown in BPMN")
    val name: String,
    @param:JsonPropertyDescription("Node type from the supported enum")
    val type: NodeType,
)

data class BpmnEdge(
    @param:JsonPropertyDescription("Unique sequence-flow id, e.g. Flow_1")
    val id: String,
    @param:JsonPropertyDescription("Source node id")
    val sourceRef: String,
    @param:JsonPropertyDescription("Target node id")
    val targetRef: String,
    @param:JsonPropertyDescription("Optional human-readable sequence-flow label")
    val name: String? = null,
)

enum class NodeType {
    START_EVENT,
    USER_TASK,
    SERVICE_TASK,
    EXCLUSIVE_GATEWAY,
    END_EVENT,
}

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
