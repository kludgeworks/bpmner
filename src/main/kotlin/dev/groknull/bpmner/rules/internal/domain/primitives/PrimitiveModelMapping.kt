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
import dev.groknull.bpmner.api.BpmnExclusiveGateway
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
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition
import dev.groknull.bpmner.api.BpmnUserTask

internal fun BpmnDefinitionContext.toPrimitiveModelContext(): PrimitiveModelContext {
    val sequenceFlows = definition.sequences.map { it.toPrimitiveFlow() }
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
            definition.nodes.map { it.toPrimitiveElement() } +
            sequenceFlows.map { it.asElement(BpmnTypeName.SEQUENCE_FLOW) },
        sequenceFlows = sequenceFlows,
    )
}

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
    is BpmnParallelGateway -> BpmnTypeName.PARALLEL_GATEWAY
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
            put("expression", eventDefinition.expression) // Backwards compatibility duplicate
        }

        is BpmnMessageEventDefinition -> put("messageRef", eventDefinition.messageRef)

        is BpmnSignalEventDefinition -> put("signalRef", eventDefinition.signalRef)

        is BpmnErrorEventDefinition -> put("errorRef", eventDefinition.errorRef)

        is BpmnEscalationEventDefinition -> put("escalationRef", eventDefinition.escalationRef)
    }
}
