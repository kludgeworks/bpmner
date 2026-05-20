/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnNode
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
 * 2. [BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT] — the gateway exists but emits
 *    fewer outbound flows than the decision has branches.
 * 3. [BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED] — a branch's `nextRef` points at an id
 *    that doesn't exist anywhere in the BPMN.
 * 4. [BpmnFidelityCode.BRANCH_FLOW_MISSING] — a branch's `nextRef` resolves but no sequence
 *    flow connects this decision's gateway to that target. Catches both missing loop
 *    back-edges and missing forward-skip edges via the same direct lookup.
 */
@Component
internal class BpmnContractFidelityChecker {
    private val logger = LoggerFactory.getLogger(BpmnContractFidelityChecker::class.java)

    private fun BpmnNode.isGateway(): Boolean = this is BpmnExclusiveGateway

    fun check(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnFidelityReport {
        val issues = mutableListOf<BpmnFidelityIssue>()
        val nodeById = definition.nodes.associateBy { it.id }
        val outgoingBySource = definition.sequences.groupBy { it.sourceRef }

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
            if (gatewayIsValid && outbound.none { it.targetRef == ref }) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.BRANCH_FLOW_MISSING,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                            "Branch '${branch.id}' of decision '${decision.id}' specifies nextRef='$ref' " +
                                "but no sequence flow connects gateway '${gateway!!.id}' to '$ref'.",
                        contractElementId = branch.id,
                        bpmnElementId = ref,
                    )
            }
        }
    }
}
