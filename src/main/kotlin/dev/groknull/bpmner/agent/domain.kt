package dev.groknull.bpmner.agent

import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

/**
 * Input to the BPMN generation agent.
 *
 * Implements [PromptContributor] so the structural rules and optional style guide are
 * injected into the system prompt automatically via [withPromptContributor].
 */
data class BpmnRequest(
    @get:JsonPropertyDescription("Natural-language description of the business process to model")
    val processDescription: String,
    @get:JsonPropertyDescription("Optional Markdown style guide that constrains naming and structure")
    val styleGuide: String? = null,
    val outputFile: String = "output.bpmn",
) : PromptContributor {
    override fun contribution(): String = buildString {
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
            """.trimIndent()
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

/** User-turn prompt asking the LLM to produce an initial [BpmnDefinition]. */
fun BpmnRequest.generationPrompt(): String = buildString {
    appendLine("Generate a BPMN definition object for this business process.")
    appendLine()
    appendLine("Business process description:")
    appendLine(processDescription)
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

@JsonClassDescription("High-level process outline used as the first staged BPMN artifact")
data class ProcessOutline(
    val request: BpmnRequest,
    @field:Valid
    val definition: BpmnDefinition,
    @field:Valid
    val metrics: OutlineMetrics,
)

data class OutlineMetrics(
    val phaseCount: Int,
    val branchCount: Int,
    val loopCount: Int,
    val subprocessCount: Int,
)

data class ValidatedOutline(
    val outline: ProcessOutline,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
) {
    val definition: BpmnDefinition
        get() = outline.definition
}

data class PhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
)

data class PhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<PhasePlan>,
)

data class ValidatedPhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)

data class ValidatedPhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<ValidatedPhasePlan>,
) {
    val definition: BpmnDefinition
        get() = phasePlans.single().definition
}

data class ComposedProcessGraph(
    val outline: ValidatedOutline,
    @field:Valid
    val definition: BpmnDefinition,
    val objectOwnersByObjectRef: Map<String, String>,
)

data class OwnedElementGraph(
    val composedGraph: ComposedProcessGraph,
    val elementOwnersByElementId: Map<String, String>,
    val objectOwnersByObjectRef: Map<String, String>,
) {
    val definition: BpmnDefinition
        get() = composedGraph.definition

    fun ownerForElementId(elementId: String?): String? = elementOwnersByElementId[elementId]

    fun ownerForObjectRef(objectRef: String?): String? = objectOwnersByObjectRef[objectRef]
}

data class LaidOutProcessGraph(
    val ownedGraph: OwnedElementGraph,
    @field:Valid
    val definition: BpmnDefinition,
) {
    fun ownerForElementId(elementId: String?): String? = ownedGraph.ownerForElementId(elementId)

    fun ownerForObjectRef(objectRef: String?): String? = ownedGraph.ownerForObjectRef(objectRef)

    fun validateOwnership(): List<String> = buildList {
        definition.nodes.forEach { node ->
            if (ownerForElementId(node.id) == null) add("Node '${node.id}' has no owner assignment")
        }
        definition.sequences.forEach { edge ->
            if (ownerForElementId(edge.id) == null) add("Edge '${edge.id}' has no owner assignment")
        }
    }
}

/**
 * Rebuild ownership maps for [newDefinition] and return a fully consistent [LaidOutProcessGraph].
 *
 * Existing node/edge owners are preserved. Elements added since the last reindex inherit the
 * process-level default owner. Removed elements are implicitly dropped because the element
 * owner map is rebuilt from scratch against [newDefinition].
 *
 * Also keeps [LaidOutProcessGraph.ownedGraph.composedGraph.definition] in sync so that the
 * two copies of the definition inside the graph never diverge.
 */
fun LaidOutProcessGraph.withUpdatedDefinition(newDefinition: BpmnDefinition): LaidOutProcessGraph {
    val defaultOwner = ownedGraph.objectOwnersByObjectRef["process"] ?: "phase:main"
    val baseObjectOwners = ownedGraph.objectOwnersByObjectRef
    val updatedObjectOwners: Map<String, String> = baseObjectOwners +
        newDefinition.nodes
            .filter { "nodes[id=${it.id}]" !in baseObjectOwners }
            .associate { "nodes[id=${it.id}]" to defaultOwner } +
        newDefinition.sequences
            .filter { "sequences[id=${it.id}]" !in baseObjectOwners }
            .associate { "sequences[id=${it.id}]" to defaultOwner }
    val newElementOwners: Map<String, String> = buildMap {
        put(newDefinition.processId, updatedObjectOwners["process"] ?: defaultOwner)
        newDefinition.nodes.forEach { node ->
            val owner = updatedObjectOwners["nodes[id=${node.id}]"] ?: defaultOwner
            put(node.id, owner)
            put("${node.id}_di", owner)
        }
        newDefinition.sequences.forEach { edge ->
            val owner = updatedObjectOwners["sequences[id=${edge.id}]"] ?: defaultOwner
            put(edge.id, owner)
            put("${edge.id}_di", owner)
        }
    }
    val updatedOwnedGraph = OwnedElementGraph(
        composedGraph = ownedGraph.composedGraph.copy(
            definition = newDefinition,
            objectOwnersByObjectRef = updatedObjectOwners,
        ),
        elementOwnersByElementId = newElementOwners,
        objectOwnersByObjectRef = updatedObjectOwners,
    )
    return LaidOutProcessGraph(ownedGraph = updatedOwnedGraph, definition = newDefinition)
}

@JsonClassDescription("Rendered BPMN XML with a stable index mapping XML elements back to the typed definition")
data class RenderedBpmn(
    val definition: BpmnDefinition,
    val xml: String,
    @field:Valid
    val elementIndex: BpmnElementIndex,
    val sourceGraph: LaidOutProcessGraph? = null,
)

@JsonClassDescription("BPMN node with semantic type and fixed diagram bounds")
data class BpmnNode(
    @field:NotBlank
    @get:JsonPropertyDescription("Unique node id, e.g. StartEvent_1")
    val id: String,
    @get:JsonPropertyDescription("Optional node label. Required for tasks, events, and diverging gateways; omit for converging gateways.")
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

@JsonClassDescription("Deterministic mapping from rendered BPMN element ids back to typed DTO objects")
data class BpmnElementIndex(
    val processId: String,
    val processObjectRef: String = "process",
    val nodeObjectRefs: Map<String, String>,
    val edgeObjectRefs: Map<String, String>,
    val shapeIdsByNodeId: Map<String, String>,
    val edgeDiagramIdsByEdgeId: Map<String, String>,
) {
    fun objectRefForElementId(elementId: String?): String? {
        if (elementId == null) {
            return null
        }
        if (elementId == processId) {
            return processObjectRef
        }
        nodeObjectRefs[elementId]?.let { return it }
        edgeObjectRefs[elementId]?.let { return it }
        shapeIdsByNodeId.entries.firstOrNull { (_, shapeId) -> shapeId == elementId }?.let { (nodeId, _) ->
            return nodeObjectRefs[nodeId]
        }
        edgeDiagramIdsByEdgeId.entries.firstOrNull { (_, diagramId) -> diagramId == elementId }?.let { (edgeId, _) ->
            return edgeObjectRefs[edgeId]
        }
        return null
    }

    fun knownElementIds(): Set<String> =
        buildSet {
            add(processId)
            addAll(nodeObjectRefs.keys)
            addAll(edgeObjectRefs.keys)
            addAll(shapeIdsByNodeId.values)
            addAll(edgeDiagramIdsByEdgeId.values)
        }
}

enum class BpmnDiagnosticSource {
    GRAPH,
    RENDER,
    XSD,
    LINT,
}

enum class BpmnRepairScope {
    OUTLINE,
    PHASE,
    COMPOSITION,
    LAYOUT,
    FULL_PROCESS,
}

@JsonClassDescription("Normalized BPMN validation or rendering diagnostic linked back to the typed definition where possible")
data class BpmnDiagnostic(
    val source: BpmnDiagnosticSource,
    val message: String,
    val rule: String? = null,
    val category: String? = null,
    val elementId: String? = null,
    val objectRef: String? = null,
    val repairScope: BpmnRepairScope? = null,
    val ownerRef: String? = null,
)

data class GlobalDiagnostics(
    val diagnostics: List<BpmnDiagnostic>,
) {
    fun countFor(source: BpmnDiagnosticSource): Int = diagnostics.count { it.source == source }
}

enum class BpmnPatchOperationType {
    SET_NODE_NAME,
    SET_EDGE_LABEL,
    ADD_NODE,
    REMOVE_NODE,
    REPLACE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    REPLACE_EDGE,
}

@JsonClassDescription("Single targeted repair operation on a BPMN definition")
data class BpmnPatchOperation(
    @get:JsonPropertyDescription("Operation type discriminator")
    val type: BpmnPatchOperationType,
    @get:JsonPropertyDescription("Node ID — required for SET_NODE_NAME, REMOVE_NODE, REPLACE_NODE")
    val nodeId: String? = null,
    @get:JsonPropertyDescription("Edge ID — required for SET_EDGE_LABEL, REMOVE_EDGE, REPLACE_EDGE")
    val edgeId: String? = null,
    @get:JsonPropertyDescription("New name value for SET_NODE_NAME")
    val name: String? = null,
    @get:JsonPropertyDescription("New label value for SET_EDGE_LABEL")
    val label: String? = null,
    @get:JsonPropertyDescription("Full BpmnNode for ADD_NODE or REPLACE_NODE")
    val node: BpmnNode? = null,
    @get:JsonPropertyDescription("Full BpmnEdge for ADD_EDGE or REPLACE_EDGE")
    val edge: BpmnEdge? = null,
)

@JsonClassDescription("Targeted repair patch — expresses minimal changes to fix specific diagnostics without a full graph rewrite")
data class BpmnRepairPatch(
    @field:NotEmpty
    @get:JsonPropertyDescription("Ordered list of patch operations to apply")
    val operations: List<BpmnPatchOperation>,
    @get:JsonPropertyDescription("Human-readable summary of the patch intent")
    val reason: String? = null,
)

sealed class PatchApplicationResult {
    data class Success(val definition: BpmnDefinition) : PatchApplicationResult()
    data class Failure(val reason: String) : PatchApplicationResult()
    data object NoOp : PatchApplicationResult()
}

/**
 * BPMN XML that has passed both XSD and bpmn-lint validation.
 */
data class ValidatedBpmnXml(
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
    val repairAttempts: Int = 0,
)

/**
 * BPMN XML that has been processed by the auto-layout service.
 */
data class LayoutedBpmnXml(
    val xml: String,
)

/**
 * Final BPMN XML that has passed validation after auto-layout.
 */
data class FinalValidatedBpmnXml(
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)

/**
 * Final result written to disk.
 */
data class BpmnResult(
    val outputFile: String,
    val xml: String,
)
