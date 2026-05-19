/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnRequest

internal class BpmnContractGenerationPromptFactory {
    @Suppress("LongMethod") // prompt assembly is a single linear narrative; splitting hurts readability
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
                "- You may infer sequence flows and routing-only converging gateways needed for valid BPMN.",
            )
            appendLine("- Leave routing-only converging gateways unnamed.")
            appendLine()
            appendLine("Identity rules:")
            appendLine(
                "- When a BPMN node realizes a ContractActivity / ContractDecision / ContractEndState," +
                    " use the contract element's id verbatim as the BPMN node id." +
                    " e.g. `act-extract-contract`, `dec-readiness`, `end-aborted-repair`.",
            )
            appendLine(
                "- The BPMN element kind goes in the `type` field (USER_TASK / SERVICE_TASK /" +
                    " EXCLUSIVE_GATEWAY / END_EVENT / …). Do not re-encode element type as a `Task_` /" +
                    " `Gateway_` / `EndEvent_` prefix in the id.",
            )
            appendLine(
                "- Synthesized routing nodes (the process start event, converging join gateways, etc.)" +
                    " have no contract id. Use stable unique ids of your choosing (e.g. `StartEvent_1`," +
                    " `Gateway_join_1`).",
            )
            appendLine()
            appendLine("Loop and back-edge rules:")
            appendLine(
                "- A sequence flow with `sourceRef == targetRef` is forbidden. Back-edges to earlier" +
                    " elements (a different `targetRef` that the process has already visited) are valid" +
                    " and required when the contract describes an iterative process.",
            )
            appendLine(
                "- When a ContractBranch carries a `nextRef`, emit a sequence flow from the decision's" +
                    " gateway to the node with that id. If `nextRef` points to an earlier activity, this" +
                    " is the loop back-edge.",
            )
            appendLine(
                "- For multi-exit loops (e.g. pass / no-progress / exhausted), emit ONE XOR gateway" +
                    " after the loop body with one outbound flow per branch — including the back-edge —" +
                    " not separate gateways per exit.",
            )
            appendLine()
            appendLine("Worked example — iterative repair loop with three exit conditions:")
            appendLine("  Contract decision `dec-validate` has three branches:")
            appendLine("    - {id: br-pass, label: \"Validation passed\", nextRef: \"end-success\"}")
            appendLine("    - {id: br-no-progress, label: \"No progress\", nextRef: \"end-no-progress\"}")
            appendLine("    - {id: br-retry, label: \"Continue\", nextRef: \"act-strategy-1\"}  // back-edge")
            appendLine("  BPMN topology:")
            appendLine("    - The decision is realized as ONE EXCLUSIVE_GATEWAY node:")
            appendLine("        BpmnNode(id=\"dec-validate\", type=EXCLUSIVE_GATEWAY, name=\"Did validation pass?\")")
            appendLine("    - Three outbound sequence flows from that gateway:")
            appendLine("        * to `end-success`, condition \"validation passed\"")
            appendLine("        * to `end-no-progress`, condition \"no progress\"")
            appendLine("        * to `act-strategy-1`, condition \"continue\"  ← back-edge")
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
                        val next = branch.nextRef?.let { " -> $it" }.orEmpty()
                        appendLine("  - ${branch.id} -> \"${branch.label}\"$condition$next")
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
                    val traces = assumption.sourceIds.joinToString(",")
                    val traceSuffix = if (traces.isNotEmpty()) " (sources: $traces)" else ""
                    appendLine("- ${assumption.id}: ${assumption.text}$traceSuffix")
                }
            }
        }
}
