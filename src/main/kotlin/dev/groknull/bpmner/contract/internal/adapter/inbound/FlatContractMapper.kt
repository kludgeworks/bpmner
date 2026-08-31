/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractFlow
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.EventGatewayBranch
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

public fun FlatProcessContract.toSealed(): ProcessContract {
    // A message/signal/escalation throw event's end-vs-intermediate placement is purely
    // positional in BPMN (does it have an outgoing sequence flow?), not a fact the model should
    // have to pre-declare by choosing which array to put it in — see issue #749. Computed once
    // here, at the one place flat wire shape becomes the sealed domain model, rather than asked
    // for on the wire and reconciled later.
    val outgoingCounts = flows.groupingBy { it.from }.eachCount()
    val (terminalThrows, midFlowThrows) = throwEvents.partition { (outgoingCounts[it.id] ?: 0) == 0 }
    return ProcessContract(
        id = id,
        processName = processName,
        summary = summary,
        start = ContractStart(trigger = start.trigger.toSealed(), sourceIds = start.sourceIds, id = start.id),
        // Embedded subprocesses are appended to `activities` as ContractActivity.SubProcess entries so
        // the activity-keyed loops in the validator and fidelity checker pick them up uniformly — a
        // subprocess IS a (composite) activity in BPMN. Membership is carried by containedActivityIds.
        activities = activities.map { it.toSealed() } + subProcesses.map { it.toSealed() },
        decisions = decisions.map { it.toSealed() },
        actors = actors,
        endStates = endStates.map { it.toSealed() } + terminalThrows.map { it.toEndState() },
        intermediateThrows = midFlowThrows.map { it.toIntermediateThrow() },
        assumptions = assumptions,
        flows = flows.map { it.toSealed() },
    )
}

/**
 * Flat → sealed for a topology edge. `branchId` is recoverable from the payload — no `kind`
 * discriminator needed on the wire — matching the pattern established for every other flat mirror.
 */
public fun FlatContractFlow.toSealed(): ContractFlow = if (branchId == null) {
    ContractFlow.Sequence(from = from, to = to)
} else {
    ContractFlow.Branch(from = from, to = to, branchId = branchId)
}

public fun FlatContractSubProcess.toSealed(): ContractActivity.SubProcess = ContractActivity.SubProcess(
    id = id,
    name = name,
    memberIds = memberIds,
    sourceIds = sourceIds,
)

public fun FlatContractActivity.toSealed(): ContractActivity = when (kind) {
    FlatActivityKind.SERVICE -> ContractActivity.Service(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.USER -> ContractActivity.User(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.SCRIPT -> ContractActivity.Script(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.MANUAL -> ContractActivity.Manual(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    // The kinds carrying a required payload field (decisionName / messageName / calledElement) are
    // split out to keep this dispatcher within detekt's per-method length limit.
    FlatActivityKind.BUSINESS_RULE, FlatActivityKind.SEND, FlatActivityKind.RECEIVE,
    FlatActivityKind.CALL_ACTIVITY,
    -> toPayloadActivity()
}

internal fun FlatContractActivity.toPayloadActivity(): ContractActivity = when (kind) {
    FlatActivityKind.BUSINESS_RULE -> ContractActivity.BusinessRule(
        id = id,
        name = name,
        decisionName = requireField(decisionName, kind, "decisionName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.SEND -> ContractActivity.Send(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.RECEIVE -> ContractActivity.Receive(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    FlatActivityKind.CALL_ACTIVITY -> ContractActivity.CallActivity(
        id = id,
        name = name,
        calledElement = requireField(calledElement, kind, "calledElement", id),
        actorId = actorId,
        sourceIds = sourceIds,
        modifiers = toModifiers(),
    )

    else ->
        throw RetryableBpmnGenerationException("toPayloadActivity called with non-payload kind: $kind")
}

public fun FlatContractEndState.toSealed(): ContractEndState = when (kind) {
    FlatEndStateKind.NORMAL -> ContractEndState.Normal(id = id, name = name, sourceIds = sourceIds)

    FlatEndStateKind.TERMINATE -> ContractEndState.Terminate(id = id, name = name, sourceIds = sourceIds)

    FlatEndStateKind.ERROR -> ContractEndState.Error(
        id = id,
        name = name,
        errorCode = requireField(errorCode, kind, "errorCode", id),
        sourceIds = sourceIds,
    )
}

/**
 * The terminal half of a partition by outgoing-flow count (see [FlatProcessContract.toSealed]) —
 * a message/signal/escalation throw event with no outgoing edge is the process's end.
 */
public fun FlatContractThrowEvent.toEndState(): ContractEndState = when (kind) {
    FlatThrowEventKind.MESSAGE -> ContractEndState.Message(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        sourceIds = sourceIds,
    )

    FlatThrowEventKind.SIGNAL -> ContractEndState.Signal(
        id = id,
        name = name,
        signalName = requireField(signalName, kind, "signalName", id),
        sourceIds = sourceIds,
    )

    FlatThrowEventKind.ESCALATION -> ContractEndState.Escalation(
        id = id,
        name = name,
        escalationCode = requireField(escalationCode, kind, "escalationCode", id),
        sourceIds = sourceIds,
    )
}

/**
 * The mid-flow half of the same partition — a throw event with at least one outgoing edge
 * continues the process.
 */
public fun FlatContractThrowEvent.toIntermediateThrow(): ContractIntermediateThrow = when (kind) {
    FlatThrowEventKind.MESSAGE -> ContractIntermediateThrow.Message(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        sourceIds = sourceIds,
    )

    FlatThrowEventKind.SIGNAL -> ContractIntermediateThrow.Signal(
        id = id,
        name = name,
        signalName = requireField(signalName, kind, "signalName", id),
        sourceIds = sourceIds,
    )

    FlatThrowEventKind.ESCALATION -> ContractIntermediateThrow.Escalation(
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
    )

    FlatBranchKind.DEFAULT -> DefaultBranch(id = id, label = label)

    FlatBranchKind.UNCONDITIONAL -> UnconditionalBranch(id = id, label = label)

    FlatBranchKind.EVENT_GATEWAY -> EventGatewayBranch(
        id = id,
        label = label,
        triggerKind = requireField(triggerKind, kind, "triggerKind", id),
        triggerDetail = requireField(triggerDetail, kind, "triggerDetail", id),
    )
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

private fun FlatContractActivity.toModifiers(): ActivityModifiers = ActivityModifiers(
    iteration = iteration?.toSealed(),
    boundaryEvents = boundaryEvents.map { it.toSealed() },
    loop = loop?.toSealed(),
)
