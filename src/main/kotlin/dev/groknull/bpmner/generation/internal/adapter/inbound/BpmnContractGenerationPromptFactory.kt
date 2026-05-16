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

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest

internal class BpmnContractGenerationPromptFactory {
    fun prompt(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
    ): String =
        buildString {
            appendLine("Generate a BPMN definition object from the validated process contract.")
            appendLine()
            appendLine("The validated ProcessContract is the primary and authoritative generation input.")
            appendLine("Use the original input only as secondary traceability context.")
            appendLine()
            appendLine("Contract-driven generation rules:")
            appendLine(
                "- Include the contract trigger, ordered activities, decisions, branches," +
                    " exception or rework paths, and end states.",
            )
            appendLine("- Represent actors only where current BPMN DTOs allow, usually in task names.")
            appendLine("- Do not add unsupported tasks, decisions, branches, actors, or end states.")
            appendLine(
                "- You may infer layout coordinates, waypoints, sequence flows," +
                    " and routing-only converging gateways needed for valid BPMN.",
            )
            appendLine("- Leave routing-only converging gateways unnamed.")
            appendLine()
            appendLine("Primary validated ProcessContract:")
            appendLine(renderContract(validatedContract.contract).trim())
            appendLine()
            appendLine("Original input for traceability only:")
            appendLine(request.processDescription)
            if (!request.styleGuide.isNullOrBlank()) {
                appendLine()
                appendLine("Style guide:")
                appendLine(request.styleGuide)
            }
        }

    // markdown rendering of the contract; one branch per contract section keeps output cohesive
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun renderContract(contract: ProcessContract): String =
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
                        appendLine("  - ${branch.id} -> \"${branch.label}\"$condition")
                    }
                }
            }

            if (contract.artifacts.isNotEmpty()) {
                appendLine()
                appendLine("## Artifacts")
                contract.artifacts.forEach { artifact ->
                    val description = artifact.description?.let { " - $it" }.orEmpty()
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
                    appendLine("- ${link.sourceId} -> ${link.targetId} [${link.classification.name.lowercase()}]")
                }
            }
        }
}
