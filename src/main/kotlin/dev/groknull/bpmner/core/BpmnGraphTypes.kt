package dev.groknull.bpmner.core

import jakarta.validation.Valid

// --- Outline and phase types (intermediate stages within generation) ---

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
