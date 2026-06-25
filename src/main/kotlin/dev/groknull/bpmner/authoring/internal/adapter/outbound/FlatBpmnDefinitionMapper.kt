/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventBasedGateway
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnManualTask
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
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
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics

/*
 * Wire-format → sealed conversion at the BPMN generation + repair LLM boundary.
 *
 * The flat shape lets the LLM emit any node kind without paying the `anyOf` schema
 * cost of the 14-subtype BpmnNode + 7-subtype BpmnEventDefinition hierarchies; the
 * sealed shape is what the rest of the pipeline operates on. Structural well-formedness
 * (kind-required field present + non-blank) is enforced here via requireField; semantic
 * validation stays in the downstream validators.
 *
 * Mappers tolerate extra nulls / fields set on the wrong kind — the LLM may emit
 * `decisionRef` on a USER_TASK and we ignore rather than reject.
 */

public fun FlatBpmnDefinition.toSealed(): BpmnDefinition = BpmnDefinition(
    processId = processId,
    processName = processName,
    nodes = nodes.map { it.toSealed() },
    sequences = sequences,
    messages = messages,
    signals = signals,
    errors = errors,
    escalations = escalations,
    annotations = annotations,
    groups = groups,
    associations = associations,
    dataObjects = dataObjects,
    dataStores = dataStores,
    dataAssociations = dataAssociations,
    participants = participants,
    lanes = lanes,
    messageFlows = messageFlows,
)

public fun FlatBpmnNode.toSealed(): BpmnNode = when (type) {
    in TASK_KINDS -> toTaskNode()
    in GATEWAY_KINDS -> toGatewayNode()
    FlatBpmnNodeKind.SUB_PROCESS -> toSubProcessNode()
    FlatBpmnNodeKind.CALL_ACTIVITY -> BpmnCallActivity(
        id = id,
        name = name,
        calledElement = requireField(calledElement, type, "calledElement", id),
        parentRef = parentRef,
    )
    else -> toEventPositionNode()
}

private val TASK_KINDS: Set<FlatBpmnNodeKind> = setOf(
    FlatBpmnNodeKind.USER_TASK,
    FlatBpmnNodeKind.SERVICE_TASK,
    FlatBpmnNodeKind.SCRIPT_TASK,
    FlatBpmnNodeKind.BUSINESS_RULE_TASK,
    FlatBpmnNodeKind.SEND_TASK,
    FlatBpmnNodeKind.RECEIVE_TASK,
    FlatBpmnNodeKind.MANUAL_TASK,
)

private val GATEWAY_KINDS: Set<FlatBpmnNodeKind> = setOf(
    FlatBpmnNodeKind.EXCLUSIVE_GATEWAY,
    FlatBpmnNodeKind.INCLUSIVE_GATEWAY,
    FlatBpmnNodeKind.PARALLEL_GATEWAY,
    FlatBpmnNodeKind.EVENT_BASED_GATEWAY,
)

internal fun FlatBpmnNode.toTaskNode(): BpmnNode {
    val mi = multiInstance?.toSealed()
    val sl = standardLoop?.toSealed()
    return when (type) {
        FlatBpmnNodeKind.USER_TASK ->
            BpmnUserTask(id = id, name = name, multiInstance = mi, standardLoop = sl, parentRef = parentRef)
        FlatBpmnNodeKind.SERVICE_TASK ->
            BpmnServiceTask(id = id, name = name, multiInstance = mi, standardLoop = sl, parentRef = parentRef)
        FlatBpmnNodeKind.SCRIPT_TASK ->
            BpmnScriptTask(id = id, name = name, multiInstance = mi, standardLoop = sl, parentRef = parentRef)
        FlatBpmnNodeKind.MANUAL_TASK ->
            BpmnManualTask(id = id, name = name, multiInstance = mi, standardLoop = sl, parentRef = parentRef)
        FlatBpmnNodeKind.BUSINESS_RULE_TASK -> BpmnBusinessRuleTask(
            id = id,
            name = name,
            decisionRef = requireField(decisionRef, type, "decisionRef", id),
            multiInstance = mi,
            standardLoop = sl,
            parentRef = parentRef,
        )
        FlatBpmnNodeKind.SEND_TASK -> BpmnSendTask(
            id = id,
            name = name,
            messageRef = requireField(messageRef, type, "messageRef", id),
            multiInstance = mi,
            standardLoop = sl,
            parentRef = parentRef,
        )
        FlatBpmnNodeKind.RECEIVE_TASK -> BpmnReceiveTask(
            id = id,
            name = name,
            messageRef = requireField(messageRef, type, "messageRef", id),
            multiInstance = mi,
            standardLoop = sl,
            parentRef = parentRef,
        )
        else ->
            throw RetryableBpmnGenerationException("toTaskNode called with non-task kind $type")
    }
}

private fun FlatMultiInstanceLoopCharacteristics.toSealed(): MultiInstanceLoopCharacteristics = MultiInstanceLoopCharacteristics(
    mode = mode,
    collectionDescription = requireField(collectionDescription, mode, "collectionDescription", "multiInstance"),
    loopCardinality = loopCardinality,
    completionCondition = completionCondition,
)

private fun FlatStandardLoopCharacteristics.toSealed(): StandardLoopCharacteristics = StandardLoopCharacteristics(
    testBefore = testBefore,
    loopCondition = loopCondition,
    loopMaximum = loopMaximum,
)

internal fun FlatBpmnNode.toGatewayNode(): BpmnNode = when (type) {
    FlatBpmnNodeKind.EXCLUSIVE_GATEWAY -> BpmnExclusiveGateway(id = id, name = name, parentRef = parentRef)
    FlatBpmnNodeKind.INCLUSIVE_GATEWAY -> BpmnInclusiveGateway(id = id, name = name, parentRef = parentRef)
    FlatBpmnNodeKind.PARALLEL_GATEWAY -> BpmnParallelGateway(id = id, name = name, parentRef = parentRef)
    FlatBpmnNodeKind.EVENT_BASED_GATEWAY -> BpmnEventBasedGateway(id = id, name = name, parentRef = parentRef)
    else ->
        throw RetryableBpmnGenerationException("toGatewayNode called with non-gateway kind $type")
}

private fun FlatBpmnNode.toSubProcessNode(): BpmnNode = BpmnSubProcess(
    id = id,
    name = name,
    triggeredByEvent = triggeredByEvent ?: false,
    parentRef = parentRef,
)

internal fun FlatBpmnNode.toEventPositionNode(): BpmnNode = when (type) {
    FlatBpmnNodeKind.START_EVENT -> BpmnStartEvent(
        id = id,
        name = name,
        eventDefinition = eventDefinition?.toSealed() ?: BpmnNoneEventDefinition,
        isInterrupting = isInterrupting ?: true,
        parentRef = parentRef,
    )
    FlatBpmnNodeKind.END_EVENT -> BpmnEndEvent(
        id = id,
        name = name,
        eventDefinition = eventDefinition?.toSealed() ?: BpmnNoneEventDefinition,
        parentRef = parentRef,
    )
    FlatBpmnNodeKind.BOUNDARY_EVENT -> BpmnBoundaryEvent(
        id = id,
        name = name,
        attachedToRef = requireField(attachedToRef, type, "attachedToRef", id),
        cancelActivity = cancelActivity ?: true,
        eventDefinition = requireNotNull(eventDefinition) { "$type ($id) requires eventDefinition" }.toSealed(),
        parentRef = parentRef,
    )
    FlatBpmnNodeKind.INTERMEDIATE_CATCH_EVENT -> BpmnIntermediateCatchEvent(
        id = id,
        name = name,
        eventDefinition = requireNotNull(eventDefinition) { "$type ($id) requires eventDefinition" }.toSealed(),
        parentRef = parentRef,
    )
    FlatBpmnNodeKind.INTERMEDIATE_THROW_EVENT -> BpmnIntermediateThrowEvent(
        id = id,
        name = name,
        eventDefinition = requireNotNull(eventDefinition) { "$type ($id) requires eventDefinition" }.toSealed(),
        parentRef = parentRef,
    )
    else ->
        throw RetryableBpmnGenerationException("toEventPositionNode called with non-event-position kind $type")
}

public fun FlatBpmnEventDefinition.toSealed(): BpmnEventDefinition = when (type) {
    FlatBpmnEventDefinitionKind.NONE -> BpmnNoneEventDefinition
    FlatBpmnEventDefinitionKind.TERMINATE -> BpmnTerminateEventDefinition

    FlatBpmnEventDefinitionKind.TIMER -> BpmnTimerEventDefinition(
        timerKind = requireField(timerKind, type, "timerKind", EVENT_CONTEXT),
        expression = requireField(expression, type, "expression", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.MESSAGE -> BpmnMessageEventDefinition(
        messageRef = requireField(messageRef, type, "messageRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.SIGNAL -> BpmnSignalEventDefinition(
        signalRef = requireField(signalRef, type, "signalRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.ERROR -> BpmnErrorEventDefinition(
        errorRef = requireField(errorRef, type, "errorRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.ESCALATION -> BpmnEscalationEventDefinition(
        escalationRef = requireField(escalationRef, type, "escalationRef", EVENT_CONTEXT),
    )
}

private const val EVENT_CONTEXT: String = "event-definition"

private fun <T : Any> requireField(value: T?, kind: Enum<*>, fieldName: String, context: String): T {
    val nonNull = requireNotNull(value) { "$kind ($context) requires $fieldName" }
    if (nonNull is CharSequence) {
        require(nonNull.isNotBlank()) { "$kind ($context) requires non-blank $fieldName" }
    }
    return nonNull
}
