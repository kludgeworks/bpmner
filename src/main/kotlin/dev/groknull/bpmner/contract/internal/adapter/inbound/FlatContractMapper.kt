/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIntermediateThrow
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
    start = start.toSealed(),
    activities = activities.map { it.toSealed() },
    decisions = decisions.map { it.toSealed() },
    actors = actors,
    artifacts = artifacts,
    endStates = endStates.map { it.toSealed() },
    intermediateThrows = intermediateThrows.map { it.toSealed() },
    assumptions = assumptions,
)

public fun FlatContractStart.toSealed(): ContractStart = ContractStart(
    trigger = trigger.toSealed(),
    sourceIds = sourceIds,
)

public fun FlatContractActivity.toSealed(): ContractActivity = when (kind) {
    FlatActivityKind.SERVICE -> ContractActivity.Service(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.USER -> ContractActivity.User(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.SCRIPT -> ContractActivity.Script(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.BUSINESS_RULE -> ContractActivity.BusinessRule(
        id = id,
        name = name,
        decisionName = requireField(decisionName, kind, "decisionName", id),
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.SEND -> ContractActivity.Send(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.RECEIVE -> ContractActivity.Receive(
        id = id,
        name = name,
        messageName = requireField(messageName, kind, "messageName", id),
        actorId = actorId,
        sourceIds = sourceIds,
    )

    FlatActivityKind.MANUAL -> ContractActivity.Manual(
        id = id,
        name = name,
        actorId = actorId,
        sourceIds = sourceIds,
    )
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

private fun <T : Any> requireField(value: T?, kind: Enum<*>, fieldName: String, context: String): T {
    val nonNull = requireNotNull(value) { "$kind ($context) requires $fieldName" }
    if (nonNull is CharSequence) {
        require(nonNull.isNotBlank()) { "$kind ($context) requires non-blank $fieldName" }
    }
    return nonNull
}
