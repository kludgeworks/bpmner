/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.primitives

/**
 * Capability bits declared on a [PrimitiveModelContext] to advertise which BPMN construct
 * families are actually populated.
 *
 * Each value corresponds to a family the production `BpmnDefinition` does **not** yet model
 * but the framework already accepts test fixtures for. Primitives that depend on a family
 * (e.g. [RequiredAssociationCheck] on [ASSOCIATIONS]) short-circuit to an empty diagnostic
 * list when the context's [PrimitiveModelContext.supportedCapabilities] omits the bit — this
 * is how dormant primitives stay genuinely dormant in production until the model gains the
 * relevant constructs in #196.
 */
internal enum class ModelCapability {
    /** `bpmn:Association` links between elements (e.g. task ↔ text annotation). */
    ASSOCIATIONS,

    /** `bpmn:MessageFlow` edges between participants. */
    MESSAGE_FLOWS,

    /** `bpmn:Participant` / `bpmn:Lane` and the `sourcePool` / `targetPool` fields on flows. */
    POOLS_AND_LANES,
}

internal data class PrimitiveModelContext(
    val elements: List<PrimitiveElement>,
    val sequenceFlows: List<PrimitiveFlow> = emptyList(),
    val associations: List<PrimitiveAssociation> = emptyList(),
    val messageFlows: List<PrimitiveFlow> = emptyList(),
    val supportedCapabilities: Set<ModelCapability> = emptySet(),
) {
    val elementsById: Map<String, PrimitiveElement> = elements.mapNotNull { element ->
        element.id?.let { it to element }
    }.toMap()
    val incomingCounts: Map<String, Int> = sequenceFlows.groupingBy { it.targetRef }.eachCount()
    val outgoingCounts: Map<String, Int> = sequenceFlows.groupingBy { it.sourceRef }.eachCount()
    val edgesFrom: Map<String, List<PrimitiveFlow>> = sequenceFlows.groupBy { it.sourceRef }
    val edgesTo: Map<String, List<PrimitiveFlow>> = sequenceFlows.groupBy { it.targetRef }

    fun supports(capability: ModelCapability): Boolean = capability in supportedCapabilities
}

internal data class PrimitiveElement(
    val id: String?,
    val typeName: String,
    val properties: Map<String, String?> = emptyMap(),
) {
    fun property(name: String): String? = when (name) {
        "id" -> id
        else -> properties[name]
    }
}

internal data class PrimitiveFlow(
    val id: String,
    val sourceRef: String,
    val targetRef: String,
    val name: String? = null,
    val conditionExpression: String? = null,
    val sourcePool: String? = null,
    val targetPool: String? = null,
) {
    fun asElement(typeName: String): PrimitiveElement = PrimitiveElement(
        id = id,
        typeName = typeName,
        properties =
        mapOf(
            "name" to name,
            "conditionExpression" to conditionExpression,
            "sourceRef" to sourceRef,
            "targetRef" to targetRef,
            "sourcePool" to sourcePool,
            "targetPool" to targetPool,
        ),
    )
}

internal data class PrimitiveAssociation(
    val id: String,
    val sourceRef: String,
    val targetRef: String,
    val typeName: String = "bpmn:Association",
)
