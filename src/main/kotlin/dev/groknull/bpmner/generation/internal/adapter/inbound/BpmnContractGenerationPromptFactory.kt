/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest

internal class BpmnContractGenerationPromptFactory(
    private val contractRenderer: ProcessContractMarkdownRenderer,
) {
    @Suppress("LongMethod", "MaxLineLength") // prompt narrative + worked-example lines stay literal
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
            appendLine(
                "- Generate exactly one START_EVENT node for `contract.start`. Put trigger semantics" +
                    " in that node's `eventDefinition`, not in a specialized start-event subtype.",
            )
            appendLine(
                "- `ContractTrigger.Timer` maps to `BpmnTimerEventDefinition`; `Message` maps to" +
                    " `BpmnMessageEventDefinition` plus a process-level `BpmnMessageRef`; `Signal`" +
                    " maps to `BpmnSignalEventDefinition` plus a process-level `BpmnSignalRef`.",
            )
            appendLine("- `ContractTrigger.None` maps to `BpmnNoneEventDefinition`.")
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
                    " SCRIPT_TASK / BUSINESS_RULE_TASK / SEND_TASK / RECEIVE_TASK / MANUAL_TASK /" +
                    " EXCLUSIVE_GATEWAY / PARALLEL_GATEWAY / END_EVENT / …). Do not re-encode" +
                    " element type as a `Task_` / `Gateway_` / `EndEvent_` prefix in the id.",
            )
            appendLine(
                "- Synthesized routing nodes (the process start event, converging join gateways, etc.)" +
                    " have no contract id. Use stable unique ids of your choosing (e.g. `StartEvent_1`," +
                    " `Gateway_join_1`).",
            )
            appendLine()
            appendLine("Activity-kind → BPMN task-type mapping (each ContractActivity carries a `kind` discriminator):")
            appendLine("- ContractActivity.Service       → BpmnServiceTask        (type=SERVICE_TASK)")
            appendLine("- ContractActivity.User          → BpmnUserTask           (type=USER_TASK)")
            appendLine("- ContractActivity.Script        → BpmnScriptTask         (type=SCRIPT_TASK)")
            appendLine(
                "- ContractActivity.BusinessRule  → BpmnBusinessRuleTask   (type=BUSINESS_RULE_TASK)" +
                    " — copy `decisionName` from the contract verbatim into `decisionRef` on the BPMN node.",
            )
            appendLine(
                "- ContractActivity.Send          → BpmnSendTask           (type=SEND_TASK) — declare" +
                    " one BpmnMessageRef in `definition.messages` whose `name` matches the contract's" +
                    " `messageName`; set `messageRef` on the task to that catalogue entry's id.",
            )
            appendLine(
                "- ContractActivity.Receive       → BpmnReceiveTask        (type=RECEIVE_TASK) — same" +
                    " catalogue convention as SendTask.",
            )
            appendLine("- ContractActivity.Manual        → BpmnManualTask         (type=MANUAL_TASK)")
            appendLine(
                "- Catalogue convention: one BpmnMessageRef per distinct messageName the contract" +
                    " mentions. Pick stable ids like `Message_DeclineNotification` from the messageName.",
            )
            appendLine()
            appendLine("Branch-kind → BpmnEdge mapping (each ContractBranch carries a `kind` discriminator):")
            appendLine(
                "- CONDITIONAL (ConditionalBranch) → BpmnEdge with `conditionExpression = branch.condition`." +
                    " The default kind for EXCLUSIVE decisions.",
            )
            appendLine(
                "- DEFAULT (DefaultBranch) → emit an outbound BpmnEdge with `conditionExpression = null`" +
                    " (no condition). You may leave `isDefault = false`; the downstream DefaultFlowAssigner" +
                    " sets `isDefault = true` from the contract and the renderer writes the gateway's" +
                    " `default=\"Flow_X\"` attribute. Valid only on EXCLUSIVE decisions. NEVER invent" +
                    " placeholder conditions like \"otherwise\" or \"all other cases\".",
            )
            appendLine(
                "- UNCONDITIONAL (UnconditionalBranch) → BpmnEdge with neither condition nor isDefault." +
                    " The kind for PARALLEL fork branches.",
            )
            appendLine()
            appendLine("Naming shape rules — follow these on your first emission to avoid lint repair rounds:")
            BpmnNamingShapeAdvice.allAdvice().forEach { advice ->
                appendLine("- ${advice.kind}: ${advice.shape}")
                appendLine("    examples: ${advice.examples.joinToString(", ") { "\"$it\"" }}")
                appendLine("    avoid:    ${advice.antiExamples.joinToString(", ") { "\"$it\"" }}")
            }
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
            appendLine(
                "    - {kind: CONDITIONAL, id: br-pass, label: \"Validation passed\", condition: \"validation passed\", nextRef: \"end-success\"}",
            )
            appendLine(
                "    - {kind: CONDITIONAL, id: br-no-progress, label: \"No progress\", condition: \"no progress\", nextRef: \"end-no-progress\"}",
            )
            appendLine(
                "    - {kind: CONDITIONAL, id: br-retry, label: \"Continue\", condition: \"continue\", nextRef: \"act-strategy-1\"}  // back-edge",
            )
            appendLine("  BPMN topology:")
            appendLine("    - The decision is realized as ONE EXCLUSIVE_GATEWAY node:")
            appendLine("        BpmnNode(id=\"dec-validate\", type=EXCLUSIVE_GATEWAY, name=\"Did validation pass?\")")
            appendLine("    - Three outbound sequence flows from that gateway with the conditions above.")
            appendLine()
            appendLine("Worked example — exclusive decision with a default branch:")
            appendLine("  Contract decision `dec-tier` (kind=EXCLUSIVE) has three branches:")
            appendLine("    - {kind: CONDITIONAL, id: br-fast, label: \"Fast-track\", condition: \"score >= 750\", nextRef: \"act-fast\"}")
            appendLine(
                "    - {kind: CONDITIONAL, id: br-standard, label: \"Standard\", condition: \"score in 600..749\", nextRef: \"act-standard\"}",
            )
            appendLine("    - {kind: DEFAULT, id: br-manual, label: \"Manual review\", nextRef: \"act-manual\"}")
            appendLine("  BPMN topology:")
            appendLine("    - One EXCLUSIVE_GATEWAY node `dec-tier`.")
            appendLine("    - Three outbound flows from `dec-tier`. The flow targeting `act-manual` has")
            appendLine("      `isDefault = true` and no conditionExpression; the renderer writes the")
            appendLine("      gateway's `default=\"Flow_manual\"` attribute.")
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
            appendLine("    - {kind: UNCONDITIONAL, id: br-it,         label: \"IT prep\",         nextRef: \"act-prep-it\"}")
            appendLine("    - {kind: UNCONDITIONAL, id: br-facilities, label: \"Facilities prep\", nextRef: \"act-prep-facilities\"}")
            appendLine("    - {kind: UNCONDITIONAL, id: br-manager,    label: \"Manager prep\",    nextRef: \"act-prep-manager\"}")
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
            appendLine(contractRenderer.render(validatedContract.contract).trim())
            appendLine()
            appendLine("Original input for traceability only:")
            appendLine(request.processDescription)
            if (!request.styleGuide.isNullOrBlank()) {
                appendLine()
                appendLine("Style guide:")
                appendLine(request.styleGuide)
            }
        }
}
