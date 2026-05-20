/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractGatewayKind
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
                    " EXCLUSIVE_GATEWAY / PARALLEL_GATEWAY / END_EVENT / …). Do not re-encode element" +
                    " type as a `Task_` / `Gateway_` / `EndEvent_` prefix in the id.",
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
            appendLine("  Contract decision `dec-validate` (kind=EXCLUSIVE) has three branches:")
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
            appendLine("Parallel-gateway rules:")
            appendLine(
                "- When a ContractDecision has `kind = PARALLEL`, realize the fork as ONE" +
                    " PARALLEL_GATEWAY node whose id equals the decision id verbatim. Each branch" +
                    " becomes one unconditional outbound flow — DO NOT add a conditionExpression.",
            )
            appendLine(
                "- Every PARALLEL fork MUST have a matching synchronising join: a second" +
                    " PARALLEL_GATEWAY node where every branch reconverges before downstream work" +
                    " proceeds. The join is a synthesised node with a stable unique id of your" +
                    " choosing (e.g. `Gateway_join_<descriptor>`) — it has no contract counterpart." +
                    " The join has no `name` (parallel joins do not ask a question).",
            )
            appendLine(
                "- After the join, emit a single outbound sequence flow to whatever comes next in" +
                    " the process (an activity, another decision, or an end event).",
            )
            appendLine()
            appendLine("Worked example — three concurrent preparation tracks rejoining:")
            appendLine("  Contract decision `dec-prep-tracks` (kind=PARALLEL) has three branches:")
            appendLine("    - {id: br-it,         label: \"IT prep\",         nextRef: \"act-prep-it\"}")
            appendLine("    - {id: br-facilities, label: \"Facilities prep\", nextRef: \"act-prep-facilities\"}")
            appendLine("    - {id: br-manager,    label: \"Manager prep\",    nextRef: \"act-prep-manager\"}")
            appendLine("  After all three complete, the process continues to `act-orientation`.")
            appendLine("  BPMN topology:")
            appendLine("    - Fork: BpmnNode(id=\"dec-prep-tracks\", type=PARALLEL_GATEWAY, name=\"Run preparation tracks\")")
            appendLine("    - Three unconditional outbound flows from the fork:")
            appendLine("        * to `act-prep-it`        (no condition)")
            appendLine("        * to `act-prep-facilities` (no condition)")
            appendLine("        * to `act-prep-manager`    (no condition)")
            appendLine("    - Synthesised join: BpmnNode(id=\"Gateway_join_prep\", type=PARALLEL_GATEWAY, name=null)")
            appendLine("    - Three inbound flows to the join (one from each track's last activity).")
            appendLine("    - One outbound flow from the join to `act-orientation`.")
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
                    val kindSuffix = if (decision.kind == ContractGatewayKind.PARALLEL) " (PARALLEL)" else ""
                    appendLine("- ${decision.id}: ${decision.question}$kindSuffix")
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
