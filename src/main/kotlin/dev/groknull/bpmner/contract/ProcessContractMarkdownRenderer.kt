/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

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
            val kindSuffix = MarkdownSuffixes.activitySuffix(activity)
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
                val suffix = MarkdownSuffixes.branchSuffix(branch)
                val next = MarkdownSuffixes.branchNextSuffix(branch)
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
            val suffix = MarkdownSuffixes.endStateSuffix(endState)
            appendLine("- ${endState.id}: ${endState.name}$suffix")
        }
    }
}

private fun StringBuilder.appendIntermediateThrows(contract: ProcessContract) {
    if (contract.intermediateThrows.isNotEmpty()) {
        appendLine()
        appendLine("## Intermediate throws")
        contract.intermediateThrows.forEach { intermediateThrow ->
            val suffix = MarkdownSuffixes.intermediateThrowSuffix(intermediateThrow)
            appendLine("- ${intermediateThrow.id}: ${intermediateThrow.name}$suffix")
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
