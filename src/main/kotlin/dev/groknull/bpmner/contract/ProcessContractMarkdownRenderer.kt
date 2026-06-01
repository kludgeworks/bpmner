/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // one helper per markdown section / suffix keeps rendering simple

package dev.groknull.bpmner.contract

import dev.groknull.bpmner.contract.ProcessContract
import org.springframework.stereotype.Component

@Component
internal class ProcessContractMarkdownRenderer {
    fun render(contract: ProcessContract): String = buildString {
        appendLine("# ${contract.processName}")
        appendLine("Trigger: ${contract.trigger}")
        appendLine()
        appendLine("## Summary")
        appendLine(contract.summary)

        appendActors(contract)
        appendActivities(contract)
        appendDecisions(contract)
        appendArtifacts(contract)
        appendIntermediateThrows(contract)
        appendEndStates(contract)
        appendAssumptions(contract)
    }
}

private fun StringBuilder.appendActors(contract: ProcessContract) {
    if (contract.actors.isNotEmpty()) {
        appendLine()
        appendLine("## Actors")
        contract.actors.forEach { actor ->
            val role = actor.role?.let { " ($it)" }.orEmpty()
            appendLine("- ${actor.id}: ${actor.name}$role")
        }
    }
}

private fun StringBuilder.appendActivities(contract: ProcessContract) {
    if (contract.activities.isNotEmpty()) {
        appendLine()
        appendLine("## Activities")
        contract.activities.forEach { activity ->
            val actor = activity.actorId?.let { " (actor: $it)" }.orEmpty()
            val kindSuffix = activitySuffix(activity)
            appendLine("- ${activity.id}: ${activity.name}$actor$kindSuffix")
        }
    }
}

private fun StringBuilder.appendDecisions(contract: ProcessContract) {
    if (contract.decisions.isNotEmpty()) {
        appendLine()
        appendLine("## Decisions")
        contract.decisions.forEach { decision ->
            val kindSuffix = if (decision.kind == ContractGatewayKind.PARALLEL) " (PARALLEL)" else ""
            appendLine("- ${decision.id}: ${decision.question}$kindSuffix")
            decision.branches.forEach { branch ->
                val suffix = branchSuffix(branch)
                val next = branchNextSuffix(branch)
                appendLine("  - ${branch.id} → \"${branch.label}\"$suffix$next")
            }
        }
    }
}

private fun StringBuilder.appendArtifacts(contract: ProcessContract) {
    if (contract.artifacts.isNotEmpty()) {
        appendLine()
        appendLine("## Artifacts")
        contract.artifacts.forEach { artifact ->
            val description = artifact.description?.let { " — $it" }.orEmpty()
            appendLine("- ${artifact.id}: ${artifact.name}$description")
        }
    }
}

private fun StringBuilder.appendEndStates(contract: ProcessContract) {
    if (contract.endStates.isNotEmpty()) {
        appendLine()
        appendLine("## End states")
        contract.endStates.forEach { endState ->
            appendLine("- ${endState.id}: ${endState.name}${endStateSuffix(endState)}")
        }
    }
}

private fun StringBuilder.appendIntermediateThrows(contract: ProcessContract) {
    if (contract.intermediateThrows.isNotEmpty()) {
        appendLine()
        appendLine("## Intermediate throws")
        contract.intermediateThrows.forEach { intermediateThrow ->
            appendLine("- ${intermediateThrow.id}: ${intermediateThrow.name}${intermediateThrowSuffix(intermediateThrow)}")
        }
    }
}

private fun StringBuilder.appendAssumptions(contract: ProcessContract) {
    if (contract.assumptions.isNotEmpty()) {
        appendLine()
        appendLine("## Assumptions")
        contract.assumptions.forEach { assumption ->
            val traces = assumption.sourceIds.joinToString(",")
            val traceSuffix = if (traces.isNotEmpty()) " (sources: $traces)" else ""
            appendLine("- ${assumption.id}: ${assumption.text}$traceSuffix")
        }
    }
}

private fun branchSuffix(branch: ContractBranch): String = when (branch) {
    is ConditionalBranch -> " if \"${branch.condition}\""
    is DefaultBranch -> " [default]"
    is UnconditionalBranch -> ""
}

private fun branchNextSuffix(branch: ContractBranch): String = branch.nextRef?.let { " → $it" }.orEmpty()

// Activity-kind suffix for the markdown line. Service is the default kind so we omit
// its label to keep the rendering quiet; Send / Receive / BusinessRule carry their
// payload reference to keep the rendered contract self-contained for the BPMN-generation
// LLM (otherwise it would have to walk back to the source prose for the messageName).
private fun activitySuffix(activity: ContractActivity): String = when (activity) {
    is ContractActivity.Service -> ""
    is ContractActivity.User -> " [USER]"
    is ContractActivity.Script -> " [SCRIPT]"
    is ContractActivity.BusinessRule -> " [BUSINESS_RULE decisionName=\"${activity.decisionName}\"]"
    is ContractActivity.Send -> " [SEND messageName=\"${activity.messageName}\"]"
    is ContractActivity.Receive -> " [RECEIVE messageName=\"${activity.messageName}\"]"
    is ContractActivity.Manual -> " [MANUAL]"
}

// End-state-kind suffix for the markdown line. Normal is the default and gets no
// label; the four typed kinds carry their payload identifier (errorCode / messageName
// / signalName / escalationCode) so the BPMN-generation LLM sees the catalogue keys
// directly without re-walking the source prose.
private fun endStateSuffix(endState: ContractEndState): String = when (endState) {
    is ContractEndState.Normal -> ""
    is ContractEndState.Terminate -> " [TERMINATE]"
    is ContractEndState.Error -> " [ERROR errorCode=\"${endState.errorCode}\"]"
    is ContractEndState.Message -> " [MESSAGE messageName=\"${endState.messageName}\"]"
    is ContractEndState.Signal -> " [SIGNAL signalName=\"${endState.signalName}\"]"
    is ContractEndState.Escalation -> " [ESCALATION escalationCode=\"${endState.escalationCode}\"]"
}

private fun intermediateThrowSuffix(intermediateThrow: ContractIntermediateThrow): String = when (intermediateThrow) {
    is ContractIntermediateThrow.Message -> " [MESSAGE messageName=\"${intermediateThrow.messageName}\"]"
    is ContractIntermediateThrow.Signal -> " [SIGNAL signalName=\"${intermediateThrow.signalName}\"]"
    is ContractIntermediateThrow.Escalation -> " [ESCALATION escalationCode=\"${intermediateThrow.escalationCode}\"]"
}
