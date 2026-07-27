/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnBusinessRuleTask
import dev.groknull.bpmner.bpmn.BpmnCallActivity
import dev.groknull.bpmner.bpmn.BpmnCompensateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventBasedGateway
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventSubProcess
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
    errors = errors,
    signals = signals,
    escalations = escalations,
    annotations = annotations,
    groups = groups,
    associations = associations,
    participants = participants,
    lanes = lanes,
    messageFlows = messageFlows,
)

public fun FlatBpmnNode.toSealed(): BpmnNode = when (type) {
    in TASK_KINDS -> toTaskNode()
    in GATEWAY_KINDS -> toGatewayNode()
    FlatBpmnNodeKind.SUB_PROCESS -> toSubProcessNode()
    FlatBpmnNodeKind.EVENT_SUB_PROCESS -> BpmnEventSubProcess(id = id, name = name, parentRef = parentRef)
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

// Cross-cutting fields shared by every task kind, computed once per node so each `when`
// arm below reuses `common.*` instead of recomputing multiInstance/standardLoop per arm.
private data class TaskCommon(
    val id: String,
    val name: String?,
    val multiInstance: MultiInstanceLoopCharacteristics?,
    val standardLoop: StandardLoopCharacteristics?,
    val isForCompensation: Boolean,
    val parentRef: String?,
)

internal fun FlatBpmnNode.toTaskNode(): BpmnNode {
    val common = TaskCommon(
        id = id,
        name = name,
        multiInstance = multiInstance?.toSealed(),
        standardLoop = standardLoop?.toSealed(),
        isForCompensation = isForCompensation ?: false,
        parentRef = parentRef,
    )

    // Local to this function only, so it doesn't add to the file's declared-function count.
    fun TaskCommon.build(
        factory: (String, String?, MultiInstanceLoopCharacteristics?, StandardLoopCharacteristics?, Boolean, String?) -> BpmnNode,
    ): BpmnNode = factory(id, name, multiInstance, standardLoop, isForCompensation, parentRef)

    return when (type) {
        FlatBpmnNodeKind.USER_TASK -> common.build(::BpmnUserTask)
        FlatBpmnNodeKind.SERVICE_TASK -> common.build(::BpmnServiceTask)
        FlatBpmnNodeKind.SCRIPT_TASK -> common.build(::BpmnScriptTask)
        FlatBpmnNodeKind.MANUAL_TASK -> common.build(::BpmnManualTask)
        FlatBpmnNodeKind.BUSINESS_RULE_TASK -> BpmnBusinessRuleTask(
            id = common.id,
            name = common.name,
            decisionRef = requireField(decisionRef, type, "decisionRef", id),
            multiInstance = common.multiInstance,
            standardLoop = common.standardLoop,
            isForCompensation = common.isForCompensation,
            parentRef = common.parentRef,
        )
        FlatBpmnNodeKind.SEND_TASK -> BpmnSendTask(
            id = common.id,
            name = common.name,
            messageRef = requireField(messageRef, type, "messageRef", id),
            multiInstance = common.multiInstance,
            standardLoop = common.standardLoop,
            isForCompensation = common.isForCompensation,
            parentRef = common.parentRef,
        )
        FlatBpmnNodeKind.RECEIVE_TASK -> BpmnReceiveTask(
            id = common.id,
            name = common.name,
            messageRef = requireField(messageRef, type, "messageRef", id),
            multiInstance = common.multiInstance,
            standardLoop = common.standardLoop,
            isForCompensation = common.isForCompensation,
            parentRef = common.parentRef,
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
        eventDefinition = requireNotNull(eventDefinition) { "$type ($id) requires eventDefinition" }.toSealed(),
        cancelActivity = cancelActivity ?: true,
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

    FlatBpmnEventDefinitionKind.ERROR -> BpmnErrorEventDefinition(
        errorRef = requireField(errorRef, type, "errorRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.SIGNAL -> BpmnSignalEventDefinition(
        signalRef = requireField(signalRef, type, "signalRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.ESCALATION -> BpmnEscalationEventDefinition(
        escalationRef = requireField(escalationRef, type, "escalationRef", EVENT_CONTEXT),
    )

    FlatBpmnEventDefinitionKind.COMPENSATE -> BpmnCompensateEventDefinition(
        activityRef = activityRef,
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
