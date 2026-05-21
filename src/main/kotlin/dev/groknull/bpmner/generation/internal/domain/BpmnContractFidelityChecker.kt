/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.kindName
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.isSemanticallyTransparent
import dev.groknull.bpmner.core.typeName
import dev.groknull.bpmner.generation.BpmnFidelityCode
import dev.groknull.bpmner.generation.BpmnFidelityIssue
import dev.groknull.bpmner.generation.BpmnFidelityReport
import dev.groknull.bpmner.generation.BpmnFidelitySeverity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deterministically checks that a generated [BpmnDefinition] preserves the topology declared
 * by its source [ProcessContract]. Complements [dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator]
 * (which validates the contract on its own) and bpmnlint (which validates the rendered XML).
 *
 * Operates under the unified-id convention established in PR #180: a contract decision's id
 * IS the BPMN gateway node's id, verbatim. Element kind is carried by the [BpmnNode] subtype
 * (see the sealed hierarchy in [dev.groknull.bpmner.core.BpmnDomain]), not by an id prefix.
 * Resolution is exact-match; no string-shape heuristics.
 *
 * Per-decision checks (each fires independently):
 * 1. [BpmnFidelityCode.DECISION_GATEWAY_MISSING] — the decision id resolves to no BPMN
 *    node, or the matching node is not a gateway type.
 * 2. [BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH] — the gateway exists but its kind
 *    (exclusive vs parallel) does not match the decision's declared [ContractGatewayKind].
 * 3. [BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT] — the gateway exists but emits
 *    fewer outbound flows than the decision has branches.
 * 4. [BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED] — a branch's `nextRef` points at an id
 *    that doesn't exist anywhere in the BPMN.
 * 5. [BpmnFidelityCode.BRANCH_FLOW_MISSING] — a branch's `nextRef` resolves but no sequence
 *    flow path connects this decision's gateway to that target — directly OR via one or more
 *    *semantically transparent* nodes (see [isSemanticallyTransparent]). The transparent-node
 *    walk accepts the legitimate generator pattern where flows pass through unnamed converging
 *    joins; missing-edge bugs are still caught.
 */
@Component
internal class BpmnContractFidelityChecker {
    private val logger = LoggerFactory.getLogger(BpmnContractFidelityChecker::class.java)

    private fun BpmnNode.isGateway(): Boolean = this is BpmnExclusiveGateway || this is BpmnParallelGateway

    private fun ContractGatewayKind.matchesGatewayType(node: BpmnNode): Boolean =
        when (this) {
            ContractGatewayKind.EXCLUSIVE -> node is BpmnExclusiveGateway
            ContractGatewayKind.PARALLEL -> node is BpmnParallelGateway
        }

    fun check(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnFidelityReport {
        val issues = mutableListOf<BpmnFidelityIssue>()
        val nodeById = definition.nodes.associateBy { it.id }
        val outgoingBySource = definition.sequences.groupBy { it.sourceRef }

        contract.activities.forEach { activity ->
            checkActivityKind(activity, nodeById, issues)
        }

        contract.decisions.forEach { decision ->
            checkDecision(decision, nodeById, outgoingBySource, issues)
        }

        val report = BpmnFidelityReport(issues = issues.toList())
        if (!report.isValid) {
            logger.warn(
                "Contract fidelity failed: {} issue(s); codes={}",
                issues.size,
                issues.map { it.code.name }.distinct().joinToString(","),
            )
        } else {
            logger.debug("Contract fidelity passed: 0 issues")
        }
        return report
    }

    @Suppress("LongMethod") // single decision's full check stays cohesive
    private fun checkDecision(
        decision: dev.groknull.bpmner.contract.ContractDecision,
        nodeById: Map<String, dev.groknull.bpmner.core.BpmnNode>,
        outgoingBySource: Map<String, List<dev.groknull.bpmner.core.BpmnEdge>>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val gateway = nodeById[decision.id]
        val gatewayIsValid = gateway != null && gateway.isGateway()

        // 1. The decision must resolve to a gateway-typed node.
        if (gateway == null) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.DECISION_GATEWAY_MISSING,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                        "Decision '${decision.id}' has no corresponding node in the generated BPMN. " +
                            "Under the unified-id convention the gateway must share the decision's id.",
                    contractElementId = decision.id,
                )
        } else if (!gateway.isGateway()) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.DECISION_GATEWAY_MISSING,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                        "Decision '${decision.id}' is realized as a ${gateway.typeName} node — " +
                            "expected a gateway type.",
                    contractElementId = decision.id,
                    bpmnElementId = gateway.id,
                )
        } else if (!decision.kind.matchesGatewayType(gateway)) {
            // 1b. Gateway exists but its kind doesn't match the contract's declared kind.
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                        "Decision '${decision.id}' declares kind=${decision.kind} but is realized as a " +
                            "${gateway.typeName} node — semantically wrong " +
                            "(${decision.kind} means \"${kindDescription(decision.kind)}\").",
                    contractElementId = decision.id,
                    bpmnElementId = gateway.id,
                )
        }

        // 2. Outbound-flow count check — only meaningful when the gateway is valid.
        val outbound = if (gatewayIsValid) outgoingBySource[gateway!!.id].orEmpty() else emptyList()
        if (gatewayIsValid && outbound.size < decision.branches.size) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                        "Decision '${decision.id}' declares ${decision.branches.size} branches but its " +
                            "gateway has only ${outbound.size} outbound sequence flow(s). The LLM has " +
                            "likely conflated branches into fewer outbound flows.",
                    contractElementId = decision.id,
                    bpmnElementId = gateway.id,
                )
        }

        // 3 + 4. Per-branch checks: nextRef resolution (always), then flow wiring (when gateway exists).
        decision.branches.forEach { branch ->
            val ref = branch.nextRef ?: return@forEach
            val targetExists = ref in nodeById
            if (!targetExists) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                            "Branch '${branch.id}' of decision '${decision.id}' has nextRef='$ref' " +
                                "which does not match any node id in the generated BPMN.",
                        contractElementId = branch.id,
                        bpmnElementId = ref,
                    )
                return@forEach
            }
            if (gatewayIsValid &&
                !targetReachableSemantically(
                    from = gateway!!,
                    targetId = ref,
                    outgoingBySource = outgoingBySource,
                    nodeById = nodeById,
                )
            ) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.BRANCH_FLOW_MISSING,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                            "Branch '${branch.id}' of decision '${decision.id}' specifies nextRef='$ref' " +
                                "but no sequence flow connects gateway '${gateway.id}' to '$ref' " +
                                "(directly or via transparent routing nodes).",
                        contractElementId = branch.id,
                        bpmnElementId = ref,
                    )
            }
        }
    }

    /**
     * Returns true if [targetId] is reachable from [from] either directly or by walking forward
     * through one or more [isSemanticallyTransparent] nodes. Bounded by [MAX_REACHABILITY_HOPS]
     * to keep the check finite on pathological topologies.
     */
    private fun targetReachableSemantically(
        from: BpmnNode,
        targetId: String,
        outgoingBySource: Map<String, List<BpmnEdge>>,
        nodeById: Map<String, BpmnNode>,
    ): Boolean {
        val direct = outgoingBySource[from.id].orEmpty()
        if (direct.any { it.targetRef == targetId }) return true
        val seen = mutableSetOf(from.id)
        var frontier = direct.map { it.targetRef }.toSet() - from.id
        repeat(MAX_REACHABILITY_HOPS) {
            if (frontier.isEmpty()) return false
            if (targetId in frontier) return true
            // Step the BFS one hop: keep only unseen, semantically-transparent nodes; collect
            // their outbound edge targets as the next frontier. Chained pipeline keeps the loop
            // body free of multi-branch jumps (detekt LoopWithTooManyJumpStatements).
            frontier =
                frontier
                    .asSequence()
                    .filter { seen.add(it) }
                    .mapNotNull { nodeById[it] }
                    .filter { it.isSemanticallyTransparent(outgoingBySource) }
                    .flatMap { outgoingBySource[it.id].orEmpty().asSequence() }
                    .map { it.targetRef }
                    .toSet()
        }
        return false
    }

    private companion object {
        // Bound the transparent-join walk. Six hops handles real-world process topologies
        // comfortably and prevents pathological loops from making the check non-terminating.
        const val MAX_REACHABILITY_HOPS = 6
    }

    private fun kindDescription(kind: ContractGatewayKind): String =
        when (kind) {
            ContractGatewayKind.EXCLUSIVE -> "pick one branch"
            ContractGatewayKind.PARALLEL -> "take all branches concurrently"
        }

    /**
     * Verifies the BPMN node that realises [activity] has the matching task subtype.
     * Skips silently when no node by the activity id is present in the BPMN — the
     * separate "activity not realised" check (not yet implemented) would catch that.
     */
    private fun checkActivityKind(
        activity: ContractActivity,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val node = nodeById[activity.id] ?: return
        if (activity.matchesTaskType(node)) return
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.ACTIVITY_TASK_KIND_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message =
                    "Activity '${activity.id}' declares kind=${activity.kindName} but is realised as a " +
                        "${node.typeName} node — expected ${activity.expectedTaskTypeName()}.",
                contractElementId = activity.id,
                bpmnElementId = node.id,
            )
    }

    private fun ContractActivity.matchesTaskType(node: BpmnNode): Boolean =
        when (this) {
            is ContractActivity.Service -> node is BpmnServiceTask
            is ContractActivity.User -> node is BpmnUserTask
            is ContractActivity.Script -> node is BpmnScriptTask
            is ContractActivity.BusinessRule -> node is BpmnBusinessRuleTask
            is ContractActivity.Send -> node is BpmnSendTask
            is ContractActivity.Receive -> node is BpmnReceiveTask
            is ContractActivity.Manual -> node is BpmnManualTask
        }

    private fun ContractActivity.expectedTaskTypeName(): String =
        when (this) {
            is ContractActivity.Service -> "SERVICE_TASK"
            is ContractActivity.User -> "USER_TASK"
            is ContractActivity.Script -> "SCRIPT_TASK"
            is ContractActivity.BusinessRule -> "BUSINESS_RULE_TASK"
            is ContractActivity.Send -> "SEND_TASK"
            is ContractActivity.Receive -> "RECEIVE_TASK"
            is ContractActivity.Manual -> "MANUAL_TASK"
        }
}
