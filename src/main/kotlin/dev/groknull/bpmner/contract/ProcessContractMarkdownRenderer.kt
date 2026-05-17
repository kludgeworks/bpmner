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
    fun render(contract: ProcessContract): String =
        buildString {
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
                    appendLine("- ${activity.id}: ${activity.name}$actor")
                }
            }

            if (contract.decisions.isNotEmpty()) {
                appendLine()
                appendLine("## Decisions")
                contract.decisions.forEach { decision ->
                    appendLine("- ${decision.id}: ${decision.question}")
                    decision.branches.forEach { branch ->
                        val condition = branch.condition?.let { " if \"$it\"" }.orEmpty()
                        appendLine("  - ${branch.id} → \"${branch.label}\"$condition")
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
                    val traces = assumption.traceLinks.joinToString(",") { it.sourceId }
                    val traceSuffix = if (traces.isNotEmpty()) " (trace: $traces)" else ""
                    appendLine("- ${assumption.id}: ${assumption.text}$traceSuffix")
                }
            }

            if (contract.traceLinks.isNotEmpty()) {
                appendLine()
                appendLine("## Trace links")
                contract.traceLinks.forEach { link ->
                    appendLine("- ${link.sourceId} → ${link.targetId} [${link.classification.name.lowercase()}]")
                }
            }
        }
}
