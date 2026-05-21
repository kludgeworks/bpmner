/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import dev.groknull.bpmner.contract.ProcessContract
import org.springframework.stereotype.Component

@Component
internal class ProcessContractMarkdownRenderer {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun render(contract: ProcessContract): String = buildString {
        appendLine("# ${contract.processName}")
        appendLine("Trigger: ${contract.trigger}")
        appendLine()
        appendLine("## Summary")
        appendLine(contract.summary)

        if (contract.actors.isNotEmpty()) {
            appendLine()
            appendLine("## Actors")
            contract.actors.forEach { actor ->
                val role = actor.role?.let { " ($it)" }.orEmpty()
                appendLine("- ${actor.id}: ${actor.name}$role")
            }
        }

        if (contract.activities.isNotEmpty()) {
            appendLine()
            appendLine("## Activities")
            contract.activities.forEach { activity ->
                val actor = activity.actorId?.let { " (actor: $it)" }.orEmpty()
                val kindSuffix = activitySuffix(activity)
                appendLine("- ${activity.id}: ${activity.name}$actor$kindSuffix")
            }
        }

        if (contract.decisions.isNotEmpty()) {
            appendLine()
            appendLine("## Decisions")
            contract.decisions.forEach { decision ->
                val kindSuffix = if (decision.kind == ContractGatewayKind.PARALLEL) " (PARALLEL)" else ""
                appendLine("- ${decision.id}: ${decision.question}$kindSuffix")
                decision.branches.forEach { branch ->
                    val suffix =
                        when (branch) {
                            is ConditionalBranch -> " if \"${branch.condition}\""
                            is DefaultBranch -> " [default]"
                            is UnconditionalBranch -> ""
                        }
                    val next = branch.nextRef?.let { " → $it" }.orEmpty()
                    appendLine("  - ${branch.id} → \"${branch.label}\"$suffix$next")
                }
            }
        }

        if (contract.artifacts.isNotEmpty()) {
            appendLine()
            appendLine("## Artifacts")
            contract.artifacts.forEach { artifact ->
                val description = artifact.description?.let { " — $it" }.orEmpty()
                appendLine("- ${artifact.id}: ${artifact.name}$description")
            }
        }

        if (contract.endStates.isNotEmpty()) {
            appendLine()
            appendLine("## End states")
            contract.endStates.forEach { endState ->
                appendLine("- ${endState.id}: ${endState.name}")
            }
        }

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
}
