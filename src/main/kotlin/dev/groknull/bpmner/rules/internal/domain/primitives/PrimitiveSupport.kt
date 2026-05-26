/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("CyclomaticComplexMethod", "TooManyFunctions")

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnEdge
import dev.groknull.bpmner.api.BpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEvent
import dev.groknull.bpmner.api.BpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnManualTask
import dev.groknull.bpmner.api.BpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnNode
import dev.groknull.bpmner.api.BpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnParallelGateway
import dev.groknull.bpmner.api.BpmnReceiveTask
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.BpmnScriptTask
import dev.groknull.bpmner.api.BpmnSendTask
import dev.groknull.bpmner.api.BpmnServiceTask
import dev.groknull.bpmner.api.BpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnStartEvent
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUserTask
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata

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

internal fun BpmnDefinitionContext.toPrimitiveModelContext(): PrimitiveModelContext {
    val sequenceFlows = definition.sequences.map { it.toPrimitiveFlow() }
    return PrimitiveModelContext(
        elements =
        listOf(
            PrimitiveElement(
                id = "definitions",
                typeName = "bpmn:Definitions",
                properties = mapOf("id" to "definitions"),
            ),
            PrimitiveElement(
                id = definition.processId,
                typeName = "bpmn:Process",
                properties = mapOf("id" to definition.processId, "name" to definition.processName),
            ),
        ) +
            definition.nodes.map { it.toPrimitiveElement() } +
            sequenceFlows.map { it.asElement("bpmn:SequenceFlow") },
        sequenceFlows = sequenceFlows,
    )
}

internal fun RuleMetadata.diagnostic(elementId: String?, messageSuffix: String? = null): RuleDiagnostic {
    val template = errorMessages["default"] ?: errorMessages.values.first()
    return RuleDiagnostic(
        diagnosticCode = diagnosticCode(),
        ruleId = id,
        severity = severity,
        message = listOfNotNull(template, messageSuffix).joinToString(": "),
        elementId = elementId,
    )
}

/**
 * Emit a single rule-scoped diagnostic indicating that a rule's Pkl configuration is
 * malformed — used by primitives that compile user-supplied regexes or other config values
 * at evaluation time. The diagnostic is `RuleSeverity.ERROR` regardless of the rule's
 * declared severity, since a misconfigured rule cannot be skipped.
 */
internal fun RuleMetadata.configError(detail: String): RuleDiagnostic = RuleDiagnostic(
    diagnosticCode = "rule-config-error",
    ruleId = id,
    severity = dev.groknull.bpmner.api.RuleSeverity.ERROR,
    message = "Rule '$id' has invalid configuration: $detail",
    elementId = null,
)

internal fun RuleMetadata.diagnosticCode(): String = errorMessages.keys.firstOrNull { it != "default" } ?: id

internal fun RuleMetadata.targetedElements(model: PrimitiveModelContext): List<PrimitiveElement> = model.elements
    .filter { element ->
        targetElements.any { target -> BpmnTypeMatcher.matches(element.typeName, target) }
    }

internal object BpmnTypeMatcher {
    internal val broadTypeNames = setOf(
        "bpmn:FlowElement",
        "bpmn:FlowNode",
        "bpmn:Task",
        "bpmn:Gateway",
        "bpmn:Event",
    )

    internal val supportedTypeNames = setOf(
        "bpmn:Definitions",
        "bpmn:Process",
        "bpmn:StartEvent",
        "bpmn:EndEvent",
        "bpmn:IntermediateCatchEvent",
        "bpmn:IntermediateThrowEvent",
        "bpmn:BoundaryEvent",
        "bpmn:UserTask",
        "bpmn:ServiceTask",
        "bpmn:ScriptTask",
        "bpmn:BusinessRuleTask",
        "bpmn:SendTask",
        "bpmn:ReceiveTask",
        "bpmn:ManualTask",
        "bpmn:ExclusiveGateway",
        "bpmn:ParallelGateway",
        "bpmn:SequenceFlow",
    )

    /**
     * Pure type-membership check. Returns `true` iff [elementType] belongs to [targetType] —
     * either exact equality or membership in one of the broad categories (`bpmn:Task`,
     * `bpmn:Gateway`, `bpmn:Event`, `bpmn:FlowNode`, `bpmn:FlowElement`).
     *
     * No special-casing for "unsupported" BPMN types (`bpmn:InclusiveGateway`,
     * `bpmn:ComplexGateway`, `bpmn:EventBasedGateway`): if production never produces them,
     * the model context simply has no elements of that type and the primitive's targeted
     * scan returns empty. Tests that exercise those types construct synthetic elements and
     * the matcher handles them like any other type.
     */
    fun matches(elementType: String, targetType: String): Boolean {
        if (targetType == elementType) return true
        return when (targetType) {
            "bpmn:FlowElement" -> elementType in flowNodeTypeNames || elementType == "bpmn:SequenceFlow"
            "bpmn:FlowNode" -> elementType in flowNodeTypeNames
            "bpmn:Task" -> elementType in taskTypeNames
            "bpmn:Gateway" -> elementType in gatewayTypeNames
            "bpmn:Event" -> elementType in eventTypeNames
            else -> false
        }
    }

    /**
     * Used by [CardinalityCheck] to skip rules targeting types the production model can't
     * produce — without this guard, a `min: 1` cardinality on `bpmn:InclusiveGateway` would
     * fire on every evaluation since the count is always zero.
     */
    fun isSupportedProductionType(typeName: String): Boolean = typeName in supportedTypeNames || typeName in broadTypeNames

    private val taskTypeNames = setOf(
        "bpmn:UserTask",
        "bpmn:ServiceTask",
        "bpmn:ScriptTask",
        "bpmn:BusinessRuleTask",
        "bpmn:SendTask",
        "bpmn:ReceiveTask",
        "bpmn:ManualTask",
    )
    private val gatewayTypeNames = setOf(
        "bpmn:ExclusiveGateway",
        "bpmn:ParallelGateway",
    )
    private val eventTypeNames = setOf(
        "bpmn:StartEvent",
        "bpmn:EndEvent",
        "bpmn:IntermediateCatchEvent",
        "bpmn:IntermediateThrowEvent",
        "bpmn:BoundaryEvent",
    )
    private val flowNodeTypeNames = taskTypeNames + gatewayTypeNames + eventTypeNames
}

internal fun PrimitiveElement.isGateway(): Boolean = BpmnTypeMatcher.matches(typeName, "bpmn:Gateway")

internal fun PrimitiveElement.isTask(): Boolean = BpmnTypeMatcher.matches(typeName, "bpmn:Task")

internal fun PrimitiveElement.isEvent(): Boolean = BpmnTypeMatcher.matches(typeName, "bpmn:Event")

internal fun BpmnNode.toPrimitiveElement(): PrimitiveElement = PrimitiveElement(
    id = id,
    typeName = bpmnTypeName(),
    properties = buildMap {
        put("id", id)
        put("name", name)
        if (this@toPrimitiveElement is BpmnBusinessRuleTask) put("decisionRef", decisionRef)
        if (this@toPrimitiveElement is BpmnSendTask) put("messageRef", messageRef)
        if (this@toPrimitiveElement is BpmnReceiveTask) put("messageRef", messageRef)
        if (this@toPrimitiveElement is BpmnBoundaryEvent) {
            put("attachedToRef", attachedToRef)
            put("cancelActivity", cancelActivity.toString())
        }
        if (this@toPrimitiveElement is BpmnEvent) {
            putAll(eventDefinitionProperties(eventDefinition))
        }
    },
)

internal fun BpmnEdge.toPrimitiveFlow(): PrimitiveFlow = PrimitiveFlow(
    id = id,
    sourceRef = sourceRef,
    targetRef = targetRef,
    name = name,
    conditionExpression = conditionExpression,
)

private fun BpmnNode.bpmnTypeName(): String = when (this) {
    is BpmnStartEvent -> "bpmn:StartEvent"
    is BpmnEndEvent -> "bpmn:EndEvent"
    is BpmnIntermediateCatchEvent -> "bpmn:IntermediateCatchEvent"
    is BpmnIntermediateThrowEvent -> "bpmn:IntermediateThrowEvent"
    is BpmnBoundaryEvent -> "bpmn:BoundaryEvent"
    is BpmnUserTask -> "bpmn:UserTask"
    is BpmnServiceTask -> "bpmn:ServiceTask"
    is BpmnScriptTask -> "bpmn:ScriptTask"
    is BpmnBusinessRuleTask -> "bpmn:BusinessRuleTask"
    is BpmnSendTask -> "bpmn:SendTask"
    is BpmnReceiveTask -> "bpmn:ReceiveTask"
    is BpmnManualTask -> "bpmn:ManualTask"
    is BpmnExclusiveGateway -> "bpmn:ExclusiveGateway"
    is BpmnParallelGateway -> "bpmn:ParallelGateway"
    else -> error("Unknown BpmnNode subtype: ${this::class.qualifiedName}")
}

private fun eventDefinitionProperties(
    eventDefinition: dev.groknull.bpmner.api.BpmnEventDefinition,
): Map<String, String?> = buildMap {
    put(
        "eventDefinition",
        when (eventDefinition) {
            is BpmnNoneEventDefinition -> "NONE"
            is BpmnTimerEventDefinition -> "TIMER"
            is BpmnMessageEventDefinition -> "MESSAGE"
            is BpmnSignalEventDefinition -> "SIGNAL"
            is BpmnErrorEventDefinition -> "ERROR"
            is BpmnEscalationEventDefinition -> "ESCALATION"
            is BpmnTerminateEventDefinition -> "TERMINATE"
            else -> "UNKNOWN"
        },
    )
    when (eventDefinition) {
        is BpmnTimerEventDefinition -> {
            put("timerKind", eventDefinition.timerKind.name)
            put("timerExpression", eventDefinition.expression)
            put("expression", eventDefinition.expression)
        }

        is BpmnMessageEventDefinition -> put("messageRef", eventDefinition.messageRef)

        is BpmnSignalEventDefinition -> put("signalRef", eventDefinition.signalRef)

        is BpmnErrorEventDefinition -> put("errorRef", eventDefinition.errorRef)

        is BpmnEscalationEventDefinition -> put("escalationRef", eventDefinition.escalationRef)
    }
}

internal interface PrimitiveRule : BpmnRule
