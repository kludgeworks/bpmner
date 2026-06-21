/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnDataObject
import dev.groknull.bpmner.bpmn.BpmnDataStore
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEvent
import dev.groknull.bpmner.bpmn.BpmnEventBasedGateway
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnGroup
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnScriptTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnServiceTask
import dev.groknull.bpmner.bpmn.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnTask
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedNode
import dev.groknull.bpmner.bpmn.BpmnUserTask

// This is the single definition-to-primitive projection pipeline; splitting the local projections
// would obscure the ordering of synthetic elements, typed nodes, artifacts, and flows.
@Suppress("LongMethod")
internal fun BpmnDefinitionContext.toPrimitiveModelContext(): PrimitiveModelContext {
    // Pool resolution: in v1 every flow node lives in the single white-box pool (the participant
    // whose processRef names this process), so every sequence flow's source and target
    // pool are that participant — `SequenceFlowWithinPool` (WITHIN_POOL) never fires. A message-flow
    // endpoint resolves to its own pool: a participant ref is its own pool, otherwise it is a flow
    // node in the white-box pool. `MessageFlowAcrossPools` (ACROSS_POOLS) fires when both ends share
    // a pool.
    val whiteBoxParticipantId = definition.participants.firstOrNull { it.processRef == definition.processId }?.id
    val participantIds = definition.participants.map { it.id }.toSet()
    fun poolFor(ref: String): String? = if (ref in participantIds) ref else whiteBoxParticipantId
    val sequenceFlows = definition.sequences.map {
        it.toPrimitiveFlow().copy(sourcePool = whiteBoxParticipantId, targetPool = whiteBoxParticipantId)
    }
    val messageFlows = definition.messageFlows.map { it.toPrimitiveFlow(::poolFor) }
    // Participants/lanes projected inline (rather than via helpers) to keep this file's top-level
    // function count under the detekt threshold. The property keys mirror what `PoolLabelCheck`
    // reads: `poolKind` (WHITE_BOX/BLACK_BOX) drives the two pool-naming rules, and `processName`
    // (the referenced process's name) lets the white-box rule assert the pool is named after it.
    val participantElements = definition.participants.map { participant ->
        PrimitiveElement(
            id = participant.id,
            typeName = BpmnTypeName.PARTICIPANT,
            properties = buildMap {
                put("id", participant.id)
                participant.name?.let { put("name", it) }
                participant.processRef?.let { put("processRef", it) }
                put("poolKind", if (participant.processRef != null) "WHITE_BOX" else "BLACK_BOX")
                if (participant.processRef == definition.processId) put("processName", definition.processName)
            },
        )
    }
    // Lane membership is the lane's flowNodeRefs only; nodes carry no lane back-reference. The label
    // is surfaced as `role` (what PoolLabelCheck.LANE_LABELS_BUSINESS_ROLES_PERFORMERS reads);
    // participantId is absent when the lane sits in a process without a surrounding collaboration.
    val laneElements = definition.lanes.map { lane ->
        PrimitiveElement(
            id = lane.id,
            typeName = BpmnTypeName.LANE,
            properties = buildMap {
                put("id", lane.id)
                lane.name?.let {
                    put("name", it)
                    put("role", it)
                }
                lane.participantId?.let { put("participantId", it) }
            },
        )
    }
    val messageFlowElements = messageFlows.map { it.asElement(BpmnTypeName.MESSAGE_FLOW) }
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
    val dataElements = dataElementsOf(definition.dataObjects, definition.dataStores)
    val groupElements = groupElementsOf(definition.groups)
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
            dataElements +
            groupElements +
            participantElements +
            laneElements +
            messageFlowElements +
            sequenceFlows.map { it.asElement(BpmnTypeName.SEQUENCE_FLOW) },
        sequenceFlows = sequenceFlows,
        associations = associations,
        messageFlows = messageFlows,
        // ASSOCIATIONS is always on (the model always carries annotations/associations). POOLS_AND_LANES
        // and MESSAGE_FLOWS flip on only when the definition actually carries those constructs, so the
        // pool/lane/message-flow rules stay dormant on ordinary single-pool-less processes.
        supportedCapabilities = buildSet {
            add(ModelCapability.ASSOCIATIONS)
            if (definition.participants.isNotEmpty()) add(ModelCapability.POOLS_AND_LANES)
            if (definition.messageFlows.isNotEmpty()) add(ModelCapability.MESSAGE_FLOWS)
        },
    )
}

private fun annotationElementsOf(annotations: List<BpmnTextAnnotation>): List<PrimitiveElement> = annotations.map {
    PrimitiveElement(
        id = it.id,
        typeName = BpmnTypeName.TEXT_ANNOTATION,
        properties = mapOf("id" to it.id, "text" to it.text),
    )
}

// Data objects/stores projected as their own `PrimitiveElement`s (like annotations) so the
// data-naming rule can target `bpmn:DataObject`/`bpmn:DataStore` by exact type name. Not nodes, so
// the sealed `when` over BpmnNode stays closed.
private fun dataElementsOf(
    dataObjects: List<BpmnDataObject>,
    dataStores: List<BpmnDataStore>,
): List<PrimitiveElement> {
    val objects = dataObjects.map {
        PrimitiveElement(
            id = it.id,
            typeName = BpmnTypeName.DATA_OBJECT,
            properties = mapOf("id" to it.id, "name" to it.name),
        )
    }
    val stores = dataStores.map {
        PrimitiveElement(
            id = it.id,
            typeName = BpmnTypeName.DATA_STORE,
            properties = mapOf("id" to it.id, "name" to it.name),
        )
    }
    return objects + stores
}

private fun groupElementsOf(groups: List<BpmnGroup>): List<PrimitiveElement> = groups.map {
    PrimitiveElement(
        id = it.id,
        typeName = BpmnTypeName.GROUP,
        properties = buildMap {
            put("id", it.id)
            it.name?.let { name -> put("name", name) }
        },
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
        // Distinct key from multiInstanceLoopCharacteristics so the standard-loop and MI rules
        // narrow to their own task sets via `appliesWhenProperty` and never overlap.
        if (this@toPrimitiveElement is BpmnTask && standardLoop != null) {
            put("standardLoopCharacteristics", "bpmn:StandardLoopCharacteristics")
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

// A message flow projects to a `PrimitiveFlow` carrying the pool of each endpoint, so the
// ACROSS_POOLS connectivity check can assert the flow crosses a pool boundary. [poolFor] resolves an
// endpoint id to its owning participant id.
internal fun BpmnMessageFlow.toPrimitiveFlow(poolFor: (String) -> String?): PrimitiveFlow = PrimitiveFlow(
    id = id,
    sourceRef = sourceRef,
    targetRef = targetRef,
    name = name,
    sourcePool = poolFor(sourceRef),
    targetPool = poolFor(targetRef),
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

    is BpmnSubProcess -> BpmnTypeName.SUB_PROCESS

    is BpmnCallActivity -> BpmnTypeName.CALL_ACTIVITY

    // Unrecognized nodes (Choreography, Transaction, etc.) carry their BPMN typename
    // verbatim — exactly what `BpmnSubset`'s `targetElements` matches on.
    is BpmnUnrecognizedNode -> bpmnType

    else -> error("Unknown BpmnNode subtype: ${this::class.qualifiedName}")
}

@Suppress("CyclomaticComplexMethod") // one arm per sealed BpmnEventDefinition subtype — the count IS the safety property
private fun eventDefinitionProperties(
    eventDefinition: dev.groknull.bpmner.bpmn.BpmnEventDefinition,
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

        is BpmnNoneEventDefinition -> Unit
        is BpmnTerminateEventDefinition -> Unit
        is BpmnUnrecognizedEventDefinition -> Unit
    }
}
