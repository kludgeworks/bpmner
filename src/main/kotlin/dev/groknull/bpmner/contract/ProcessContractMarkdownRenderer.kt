/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import dev.groknull.bpmner.contract.ProcessContract
import org.springframework.stereotype.Component

@Component
internal class ProcessContractMarkdownRenderer {
    enum class Style(
        val branchArrow: String,
        val artifactSeparator: String,
        val traceArrow: String,
    ) {
        CANONICAL(branchArrow = "→", artifactSeparator = " — ", traceArrow = "→"),
        ASCII(branchArrow = "->", artifactSeparator = " - ", traceArrow = "->"),
    }

    fun render(contract: ProcessContract): String = render(contract, Style.CANONICAL)

    fun render(
        contract: ProcessContract,
        style: Style,
    ): String =
        buildString {
            appendLine("# ${contract.processName}")
            appendLine("Trigger: ${contract.trigger}")
            appendLine()
            appendLine("## Summary")
            appendLine(contract.summary)

            renderActors(contract)
            renderActivities(contract)
            renderDecisions(contract, style)
            renderArtifacts(contract, style)
            renderEndStates(contract)
            renderAssumptions(contract)
            renderTraceLinks(contract, style)
        }

    private fun StringBuilder.renderActors(contract: ProcessContract) {
        if (contract.actors.isNotEmpty()) {
            appendLine()
            appendLine("## Actors")
            contract.actors.forEach { actor ->
                val role = actor.role?.let { " ($it)" }.orEmpty()
                appendLine("- ${actor.id}: ${actor.name}$role")
            }
        }
    }

    private fun StringBuilder.renderActivities(contract: ProcessContract) {
        if (contract.activities.isNotEmpty()) {
            appendLine()
            appendLine("## Activities")
            contract.activities.forEach { activity ->
                val actor = activity.actorId?.let { " (actor: $it)" }.orEmpty()
                appendLine("- ${activity.id}: ${activity.name}$actor")
            }
        }
    }

    private fun StringBuilder.renderDecisions(
        contract: ProcessContract,
        style: Style,
    ) {
        if (contract.decisions.isNotEmpty()) {
            appendLine()
            appendLine("## Decisions")
            contract.decisions.forEach { decision ->
                appendLine("- ${decision.id}: ${decision.question}")
                renderBranches(decision.branches, style)
            }
        }
    }

    private fun StringBuilder.renderBranches(
        branches: List<ContractBranch>,
        style: Style,
    ) {
        branches.forEach { branch ->
            val condition = branch.condition?.let { " if \"$it\"" }.orEmpty()
            appendLine("  - ${branch.id} ${style.branchArrow} \"${branch.label}\"$condition")
        }
    }

    private fun StringBuilder.renderArtifacts(
        contract: ProcessContract,
        style: Style,
    ) {
        if (contract.artifacts.isNotEmpty()) {
            appendLine()
            appendLine("## Artifacts")
            contract.artifacts.forEach { artifact ->
                val description = artifact.description?.let { "${style.artifactSeparator}$it" }.orEmpty()
                appendLine("- ${artifact.id}: ${artifact.name}$description")
            }
        }
    }

    private fun StringBuilder.renderEndStates(contract: ProcessContract) {
        if (contract.endStates.isNotEmpty()) {
            appendLine()
            appendLine("## End states")
            contract.endStates.forEach { endState ->
                appendLine("- ${endState.id}: ${endState.name}")
            }
        }
    }

    private fun StringBuilder.renderAssumptions(contract: ProcessContract) {
        if (contract.assumptions.isNotEmpty()) {
            appendLine()
            appendLine("## Assumptions")
            contract.assumptions.forEach { assumption ->
                val traces = assumption.traceLinks.joinToString(",") { it.sourceId }
                val traceSuffix = if (traces.isNotEmpty()) " (trace: $traces)" else ""
                appendLine("- ${assumption.id}: ${assumption.text}$traceSuffix")
            }
        }
    }

    private fun StringBuilder.renderTraceLinks(
        contract: ProcessContract,
        style: Style,
    ) {
        if (contract.traceLinks.isNotEmpty()) {
            appendLine()
            appendLine("## Trace links")
            contract.traceLinks.forEach { link ->
                appendLine("- ${link.sourceId} ${style.traceArrow} ${link.targetId} [${link.classification.name.lowercase()}]")
            }
        }
    }
}
