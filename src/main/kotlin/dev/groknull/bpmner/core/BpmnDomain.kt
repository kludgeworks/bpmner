package dev.groknull.bpmner.core

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

enum class GenerationMode {
    SINGLE_SHOT,
    INTERACTIVE,
}

data class BpmnRequest(
    @get:JsonPropertyDescription("Natural-language description of the business process to model")
    val processDescription: String,
    @get:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    val styleGuide: String? = null,
    val outputFile: String = "output.bpmn",
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    @field:Valid
    @get:JsonPropertyDescription("Ordered answered clarification history for this generation request")
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
) : PromptContributor {
    override fun contribution(): String =
        buildString {
            appendLine(
                """
                You are a BPMN process design expert. Given a business process description, generate
                a typed BPMN process definition object that can be converted to valid BPMN 2.0 XML.

                Rules:
                - Return a single process definition object with processId, processName, nodes, and sequences.
                - Every node id and sequence id must be unique.
                - Every sequence sourceRef and targetRef must reference an existing node id.
                - Include at least one START_EVENT and one END_EVENT.
                - Use clear, descriptive business names on tasks and events.
                - Name diverging gateways as decision questions; leave converging gateways unnamed.
                - Keep process topology coherent with no dangling references or self-loop sequence flows.
                - Every node must include explicit bounds with x, y, width, and height.
                - Every sequence must include at least two waypoints that define its diagram path.
                - Use conditionExpression on conditional gateway branches when needed.
                - The layout should be coherent and readable because it will be emitted directly into BPMNDI.

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

@JsonClassDescription("Typed BPMN process definition including topology and diagram layout")
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

@JsonClassDescription("BPMN node with semantic type and fixed diagram bounds")
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
    @field:Valid
    @get:JsonPropertyDescription("Diagram bounds for this node in BPMNDI coordinates")
    val bounds: BpmnBounds,
)

@JsonClassDescription("Directed BPMN sequence flow with optional label, condition, and diagram waypoints")
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
    @field:Size(min = 2)
    @field:Valid
    @get:JsonPropertyDescription("Ordered BPMNDI waypoints describing the edge path")
    val waypoints: List<BpmnWaypoint>,
)

@JsonClassDescription("Diagram bounds for a BPMN node")
data class BpmnBounds(
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Left X coordinate of the node")
    val x: Double,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Top Y coordinate of the node")
    val y: Double,
    @field:Positive
    @get:JsonPropertyDescription("Node width")
    val width: Double,
    @field:Positive
    @get:JsonPropertyDescription("Node height")
    val height: Double,
)

@JsonClassDescription("Diagram waypoint for a BPMN edge")
data class BpmnWaypoint(
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Waypoint X coordinate")
    val x: Double,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Waypoint Y coordinate")
    val y: Double,
)

enum class NodeType {
    START_EVENT,
    USER_TASK,
    SERVICE_TASK,
    EXCLUSIVE_GATEWAY,
    END_EVENT,
}
