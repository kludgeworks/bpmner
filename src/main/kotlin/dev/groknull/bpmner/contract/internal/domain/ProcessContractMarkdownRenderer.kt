/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.contract.internal.domain

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
