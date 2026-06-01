/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnEdge
import dev.groknull.bpmner.api.BpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEvent
import dev.groknull.bpmner.api.BpmnEventBasedGateway
import dev.groknull.bpmner.api.BpmnEventDefinition
import dev.groknull.bpmner.api.BpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnInclusiveGateway
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnManualTask
import dev.groknull.bpmner.api.BpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnNode
import dev.groknull.bpmner.api.BpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnParallelGateway
import dev.groknull.bpmner.api.BpmnReceiveTask
import dev.groknull.bpmner.api.BpmnScriptTask
import dev.groknull.bpmner.api.BpmnSendTask
import dev.groknull.bpmner.api.BpmnServiceTask
import dev.groknull.bpmner.api.BpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnStartEvent
import dev.groknull.bpmner.api.BpmnTask
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTextAnnotation
import dev.groknull.bpmner.api.BpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.api.BpmnUnrecognizedNode
import dev.groknull.bpmner.api.BpmnUserTask

internal fun BpmnDefinitionContext.toPrimitiveModelContext(): PrimitiveModelContext {
    val sequenceFlows = definition.sequences.map { it.toPrimitiveFlow() }
    // One synthetic `bpmndi:BPMNDiagram` element per counted diagram so `CardinalityCheck`
    // (driving `NoDuplicateDiagrams`) can count them via `model.elements`. The id is null
    // because diagrams have no element-level id in the semantic model.
    val diagramElements = List(definition.diagramCount) {
        PrimitiveElement(id = null, typeName = BpmnTypeName.DIAGRAM)
    }
    // Each event's event-definition is also emitted as its own `PrimitiveElement` so rules
    // like `BpmnSubset` can target typenames such as `bpmn:CompensateEventDefinition` or
    // `bpmn:EscalationEventDefinition`. Rules that read event defs through the parent event's
    // properties are unaffected — these extra elements are invisible to any rule whose
    // `targetElements` doesn't list event-def typenames.
    val eventDefinitionElements = definition.nodes.flatMap { node ->
        if (node is BpmnEvent) {
            val typeName = node.eventDefinition.bpmnTypeName()
            // `NONE` is a placeholder for "no event-definition child"; skip it (no element
            // to surface).
            if (typeName != null) {
                listOf(
                    PrimitiveElement(
                        id = "${node.id}.eventDefinition",
                        typeName = typeName,
                        properties = mapOf("parentEventId" to node.id),
                    ),
                )
            } else {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
    // Resolve each annotated element id → the text of its linked annotation, so a task's
    // associated annotation wording is readable as a property (see `toPrimitiveElement`). BPMN
    // models the link as sourceRef = annotated element, targetRef = annotation.
    val annotationTextById = definition.annotations.associate { it.id to it.text }
    // Join (don't overwrite) when an element has several annotations, so a general note can't
    // hide the iteration-word annotation the vocabulary checks look for.
    val annotationTextByElementId = definition.associations
        .mapNotNull { association -> annotationTextById[association.targetRef]?.let { association.sourceRef to it } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, texts) -> texts.joinToString(" ") }
    val annotationElements = annotationElementsOf(definition.annotations)
    val associations = definition.associations.map { association ->
        PrimitiveAssociation(id = association.id, sourceRef = association.sourceRef, targetRef = association.targetRef)
    }
    return PrimitiveModelContext(
        elements =
        listOf(
            PrimitiveElement(
                id = "definitions",
                typeName = BpmnTypeName.DEFINITIONS,
                properties = mapOf("id" to "definitions"),
            ),
            PrimitiveElement(
                id = definition.processId,
                typeName = BpmnTypeName.PROCESS,
                properties = mapOf("id" to definition.processId, "name" to definition.processName),
            ),
        ) +
            diagramElements +
            definition.nodes.map { it.toPrimitiveElement(annotationTextByElementId) } +
            eventDefinitionElements +
            annotationElements +
            sequenceFlows.map { it.asElement(BpmnTypeName.SEQUENCE_FLOW) },
        sequenceFlows = sequenceFlows,
        associations = associations,
        // The model now always carries annotations/associations, so the capability is on; the
        // loop/MI rules narrow to relevant elements via `appliesWhenProperty`.
        supportedCapabilities = setOf(ModelCapability.ASSOCIATIONS),
    )
}

private fun annotationElementsOf(annotations: List<BpmnTextAnnotation>): List<PrimitiveElement> = annotations.map {
    PrimitiveElement(
        id = it.id,
        typeName = BpmnTypeName.TEXT_ANNOTATION,
        properties = mapOf("id" to it.id, "text" to it.text),
    )
}

private fun BpmnEventDefinition.bpmnTypeName(): String? = when (this) {
    is BpmnNoneEventDefinition -> null
    is BpmnTimerEventDefinition -> "bpmn:TimerEventDefinition"
    is BpmnMessageEventDefinition -> "bpmn:MessageEventDefinition"
    is BpmnSignalEventDefinition -> "bpmn:SignalEventDefinition"
    is BpmnErrorEventDefinition -> "bpmn:ErrorEventDefinition"
    is BpmnEscalationEventDefinition -> "bpmn:EscalationEventDefinition"
    is BpmnTerminateEventDefinition -> "bpmn:TerminateEventDefinition"
    is BpmnUnrecognizedEventDefinition -> typeName
    else -> null
}

internal fun BpmnNode.toPrimitiveElement(
    annotationTextByElementId: Map<String, String> = emptyMap(),
): PrimitiveElement = PrimitiveElement(
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
        // Presence flag the loop/MI rules narrow on via `appliesWhenProperty`. Only set on tasks
        // that actually carry a multi-instance marker, so ordinary tasks stay out of scope.
        if (this@toPrimitiveElement is BpmnTask && multiInstance != null) {
            put("multiInstanceLoopCharacteristics", "bpmn:MultiInstanceLoopCharacteristics")
        }
        // Text of the annotation linked to this element (if any), letting vocabulary checks read
        // the annotation's wording without re-typing the element.
        annotationTextByElementId[id]?.let { put("annotationText", it) }
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

@Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
private fun BpmnNode.bpmnTypeName(): String = when (this) {
    is BpmnStartEvent -> BpmnTypeName.START_EVENT

    is BpmnEndEvent -> BpmnTypeName.END_EVENT

    is BpmnIntermediateCatchEvent -> BpmnTypeName.INTERMEDIATE_CATCH_EVENT

    is BpmnIntermediateThrowEvent -> BpmnTypeName.INTERMEDIATE_THROW_EVENT

    is BpmnBoundaryEvent -> BpmnTypeName.BOUNDARY_EVENT

    is BpmnUserTask -> BpmnTypeName.USER_TASK

    is BpmnServiceTask -> BpmnTypeName.SERVICE_TASK

    is BpmnScriptTask -> BpmnTypeName.SCRIPT_TASK

    is BpmnBusinessRuleTask -> BpmnTypeName.BUSINESS_RULE_TASK

    is BpmnSendTask -> BpmnTypeName.SEND_TASK

    is BpmnReceiveTask -> BpmnTypeName.RECEIVE_TASK

    is BpmnManualTask -> BpmnTypeName.MANUAL_TASK

    is BpmnExclusiveGateway -> BpmnTypeName.EXCLUSIVE_GATEWAY

    is BpmnInclusiveGateway -> BpmnTypeName.INCLUSIVE_GATEWAY

    is BpmnParallelGateway -> BpmnTypeName.PARALLEL_GATEWAY

    is BpmnEventBasedGateway -> BpmnTypeName.EVENT_BASED_GATEWAY

    // Unrecognized nodes (Choreography, Transaction, etc.) carry their BPMN typename
    // verbatim — exactly what `BpmnSubset`'s `targetElements` matches on.
    is BpmnUnrecognizedNode -> bpmnType

    else -> error("Unknown BpmnNode subtype: ${this::class.qualifiedName}")
}

@Suppress("CyclomaticComplexMethod") // one arm per sealed BpmnEventDefinition subtype — the count IS the safety property
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

            // Surface unrecognized event-definition typenames as a descriptive token so
            // LLM-prompt and logging consumers get a useful string. The rule engine flags
            // these independently via `BpmnSubset`.
            is BpmnUnrecognizedEventDefinition -> "UNRECOGNIZED:${eventDefinition.typeName}"

            else -> "UNKNOWN"
        },
    )
    when (eventDefinition) {
        is BpmnTimerEventDefinition -> {
            put("timerKind", eventDefinition.timerKind.name)
            put("timerExpression", eventDefinition.expression)
            put("expression", eventDefinition.expression) // Backwards compatibility duplicate
        }

        is BpmnMessageEventDefinition -> put("messageRef", eventDefinition.messageRef)

        is BpmnSignalEventDefinition -> put("signalRef", eventDefinition.signalRef)

        is BpmnErrorEventDefinition -> put("errorRef", eventDefinition.errorRef)

        is BpmnEscalationEventDefinition -> put("escalationRef", eventDefinition.escalationRef)
    }
}
