/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.NodeType
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
 * Catches the common LLM failure of flattening iterative contract loops into linear chains by
 * verifying:
 * 1. Every [ContractBranch.nextRef] resolves to an existing node id.
 * 2. Every [ContractDecision] has at least one gateway with outbound degree >= its branch count.
 * 3. Branches that target an earlier activity (back-edge) have a corresponding sequence flow.
 */
@Component
internal class BpmnContractFidelityChecker {
    private val logger = LoggerFactory.getLogger(BpmnContractFidelityChecker::class.java)

    private companion object {
        // BPMN node types that represent gateways. We currently support EXCLUSIVE_GATEWAY only
        // (per the NodeType enum); extend as the enum grows (inclusive, parallel, event-based).
        private val GATEWAY_TYPES = setOf(NodeType.EXCLUSIVE_GATEWAY)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun check(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnFidelityReport {
        val issues = mutableListOf<BpmnFidelityIssue>()
        val nodeIds = definition.nodes.map { it.id }.toSet()
        val activityIndexById = contract.activities.withIndex().associate { (i, a) -> a.id to i }

        contract.decisions.forEach { decision ->
            // 1. Every nextRef must resolve to a known node id.
            decision.branches.forEach { branch ->
                val ref = branch.nextRef
                if (ref != null && ref !in nodeIds) {
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
                }
            }

            // 2. There must be a gateway with outbound degree >= the decision's branch count.
            //    Gateways are identified by `BpmnNode.type`, not by id prefix — element kind is
            //    carried structurally, not via naming convention.
            val branchCount = decision.branches.size
            val maxOutboundDegree =
                definition.nodes
                    .filter { it.type in GATEWAY_TYPES }
                    .maxOfOrNull { gw -> definition.sequences.count { it.sourceRef == gw.id } }
                    ?: 0
            if (maxOutboundDegree < branchCount) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                            "Decision '${decision.id}' declares $branchCount branches but no gateway in " +
                                "the BPMN has that many outbound sequence flows (max observed: $maxOutboundDegree). " +
                                "The LLM has likely conflated branches into fewer outbound flows.",
                        contractElementId = decision.id,
                    )
            }

            // 3. Back-edges: a branch.nextRef pointing to an earlier activity must produce a
            //    back-edge in the topology. We detect this by counting incoming edges: a normal
            //    forward-flow activity has exactly one incoming edge (the entry); a node that is
            //    also the target of a back-edge has at least two incoming edges (entry + back-edge).
            decision.branches.forEach { branch ->
                val ref = branch.nextRef ?: return@forEach
                val targetActivityIndex = activityIndexById[ref] ?: return@forEach
                val isBackEdgeTarget = targetActivityIndex < (contract.activities.size - 1)
                if (!isBackEdgeTarget) return@forEach
                val incomingCount = definition.sequences.count { it.targetRef == ref }
                if (incomingCount < 2) {
                    issues +=
                        BpmnFidelityIssue(
                            code = BpmnFidelityCode.LOOP_BACK_EDGE_MISSING,
                            severity = BpmnFidelitySeverity.ERROR,
                            message =
                                "Branch '${branch.id}' targets earlier activity '$ref' (back-edge) but " +
                                    "only $incomingCount sequence flow(s) reach '$ref'. A back-edge requires " +
                                    "the activity to have at least one additional incoming flow on top of its " +
                                    "forward entry.",
                            contractElementId = branch.id,
                            bpmnElementId = ref,
                        )
                }
            }
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
}
