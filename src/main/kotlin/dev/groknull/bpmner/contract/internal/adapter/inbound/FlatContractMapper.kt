/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.api.BoundaryEventKind
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBoundaryEvent
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch

/*
 * Wire-format → sealed conversion at the LLM-agent boundary.
 *
 * The flat shape lets the LLM emit any kind without paying the `anyOf` schema cost
 * of a sealed hierarchy; the sealed shape is what the rest of the pipeline operates on.
 * Structural well-formedness (kind-required field present) is checked here via
 * requireNotNull; cross-element semantics stays in BpmnContractValidator.
 *
 * Mappers are tolerant of extra nulls / fields set on the wrong kind — the LLM may
 * emit `decisionName` on a SERVICE activity and we ignore rather than reject. The
 * dispatch only ever reads the fields the chosen kind needs.
 */

public fun FlatProcessContract.toSealed(): ProcessContract = ProcessContract(
    id = id,
    processName = processName,
    summary = summary,
    start = ContractStart(trigger = start.trigger.toSealed(), sourceIds = start.sourceIds),
    activities = activities.map { it.toSealed() },
    decisions = decisions.map { it.toSealed() },
    actors = actors,
    artifacts = artifacts,
    endStates = endStates.map { it.toSealed() },
    intermediateThrows = intermediateThrows.map { it.toSealed() },
    assumptions = assumptions,
)

public fun FlatContractActivity.toSealed(): ContractActivity = when (kind) {
    FlatActivityKind.SERVICE -> ContractActivity.Service(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    FlatActivityKind.USER -> ContractActivity.User(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    FlatActivityKind.SCRIPT -> ContractActivity.Script(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    FlatActivityKind.MANUAL -> ContractActivity.Manual(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    // The three kinds carrying a required payload field (decisionName / messageName) are split out
    // to keep this dispatcher within detekt's per-method length limit.
    FlatActivityKind.BUSINESS_RULE, FlatActivityKind.SEND, FlatActivityKind.RECEIVE -> toPayloadActivity()
}

private fun FlatContractActivity.toPayloadActivity(): ContractActivity = when (kind) {
    FlatActivityKind.BUSINESS_RULE -> ContractActivity.BusinessRule(
        id = id,
        name = name,
        decisionName = requireField(decisionName, kind, "decisionName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    FlatActivityKind.SEND -> ContractActivity.Send(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    FlatActivityKind.RECEIVE -> ContractActivity.Receive(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        iteration = iteration?.toSealed(),
        boundaryEvents = boundaryEvents.map { it.toSealed() },
    )

    else -> error("toPayloadActivity called with non-payload kind: $kind")
}

private fun FlatContractIteration.toSealed(): ContractIteration = ContractIteration(
    mode = mode,
    collectionDescription = requireField(collectionDescription, mode, "collectionDescription", "iteration"),
    loopCardinality = loopCardinality,
    completionCondition = completionCondition,
)

private fun FlatContractBoundaryEvent.toSealed(): ContractBoundaryEvent = ContractBoundaryEvent(
    kind = kind,
    label = requireField(label, kind, "label", "boundaryEvent"),
    nextRef = requireField(nextRef, kind, "nextRef", "boundaryEvent"),
    cancelActivity = cancelActivity,
    detail = if (kind == BoundaryEventKind.TIMER) requireField(detail, kind, "detail", "boundaryEvent") else detail,
)

public fun FlatContractEndState.toSealed(): ContractEndState = when (kind) {
    FlatEndStateKind.NORMAL -> ContractEndState.Normal(id = id, name = name, sourceIds = sourceIds)

    FlatEndStateKind.TERMINATE -> ContractEndState.Terminate(id = id, name = name, sourceIds = sourceIds)

    FlatEndStateKind.ERROR -> ContractEndState.Error(
        id = id,
        name = name,
        errorCode = requireField(errorCode, kind, "errorCode", id),
        sourceIds = sourceIds,
    )

    FlatEndStateKind.MESSAGE -> ContractEndState.Message(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        sourceIds = sourceIds,
    )

    FlatEndStateKind.SIGNAL -> ContractEndState.Signal(
        id = id,
        name = name,
        signalName = requireField(signalName, kind, "signalName", id),
        sourceIds = sourceIds,
    )

    FlatEndStateKind.ESCALATION -> ContractEndState.Escalation(
        id = id,
        name = name,
        escalationCode = requireField(escalationCode, kind, "escalationCode", id),
        sourceIds = sourceIds,
    )
}

public fun FlatContractIntermediateThrow.toSealed(): ContractIntermediateThrow = when (kind) {
    FlatIntermediateThrowKind.MESSAGE -> ContractIntermediateThrow.Message(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        sourceIds = sourceIds,
    )

    FlatIntermediateThrowKind.SIGNAL -> ContractIntermediateThrow.Signal(
        id = id,
        name = name,
        signalName = requireField(signalName, kind, "signalName", id),
        sourceIds = sourceIds,
    )

    FlatIntermediateThrowKind.ESCALATION -> ContractIntermediateThrow.Escalation(
        id = id,
        name = name,
        escalationCode = requireField(escalationCode, kind, "escalationCode", id),
        sourceIds = sourceIds,
    )
}

public fun FlatContractBranch.toSealed(): ContractBranch = when (kind) {
    FlatBranchKind.CONDITIONAL -> ConditionalBranch(
        id = id,
        label = label,
        condition = requireField(condition, kind, "condition", id),
        nextRef = nextRef,
    )

    FlatBranchKind.DEFAULT -> DefaultBranch(id = id, label = label, nextRef = nextRef)

    FlatBranchKind.UNCONDITIONAL -> UnconditionalBranch(id = id, label = label, nextRef = nextRef)
}

public fun FlatContractTrigger.toSealed(): ContractTrigger = when (type) {
    FlatTriggerKind.NONE -> ContractTrigger.None(description = description)

    FlatTriggerKind.TIMER -> ContractTrigger.Timer(
        timerKind = requireField(timerKind, type, "timerKind", description),
        expression = requireField(expression, type, "expression", description),
        description = description,
    )

    FlatTriggerKind.MESSAGE -> ContractTrigger.Message(
        messageName = requireField(messageName, type, "messageName", description),
        description = description,
    )

    FlatTriggerKind.SIGNAL -> ContractTrigger.Signal(
        signalName = requireField(signalName, type, "signalName", description),
        description = description,
    )
}

public fun FlatContractDecision.toSealed(): ContractDecision = ContractDecision(
    id = id,
    question = question,
    branches = branches.map { it.toSealed() },
    kind = kind,
    sourceIds = sourceIds,
)
