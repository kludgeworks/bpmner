/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.ContractValidationIssue
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import org.springframework.stereotype.Component

@Component
internal class BpmnContractValidator {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun validate(contract: ProcessContract): ContractValidationReport {
        val issues = mutableListOf<ContractValidationIssue>()

        if (contract.processName.isBlank()) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.MISSING_PROCESS_NAME,
                    severity = ContractIssueSeverity.ERROR,
                    message = "process name must not be blank",
                    targetId = contract.id,
                )
        }
        if (contract.trigger.isBlank()) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    severity = ContractIssueSeverity.ERROR,
                    message = "process trigger must not be blank",
                    targetId = contract.id,
                )
        } else if (contract.triggerSourceIds.isEmpty()) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.TRIGGER_WITHOUT_TRACE,
                    severity = ContractIssueSeverity.ERROR,
                    message = "process trigger must carry at least one source id",
                    targetId = contract.id,
                )
        }
        if (contract.endStates.isEmpty()) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.NO_END_STATE,
                    severity = ContractIssueSeverity.ERROR,
                    message = "process contract must declare at least one end state",
                    targetId = contract.id,
                )
        }
        if (contract.activities.size < MIN_ACTIVITIES) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.INSUFFICIENT_ACTIVITIES,
                    severity = ContractIssueSeverity.ERROR,
                    message =
                        "process contract must declare at least $MIN_ACTIVITIES activities" +
                            " (found ${contract.activities.size})",
                    targetId = contract.id,
                )
        }

        contract.decisions.forEach { decision ->
            if (decision.branches.size < MIN_DECISION_BRANCHES) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.DECISION_BRANCH_TOO_FEW,
                        severity = ContractIssueSeverity.ERROR,
                        message =
                            "decision must declare at least $MIN_DECISION_BRANCHES branches" +
                                " (found ${decision.branches.size})",
                        targetId = decision.id,
                    )
            }
            // Cross-cutting branch-kind invariants over the sealed ContractBranch hierarchy.
            // Per-branch field constraints (non-blank label, non-blank condition on ConditionalBranch)
            // are enforced by Jakarta validation on the subtype constructors, not here.
            val defaults = decision.branches.filterIsInstance<DefaultBranch>()
            if (defaults.size > 1) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.DECISION_MULTIPLE_DEFAULTS,
                        severity = ContractIssueSeverity.ERROR,
                        message =
                            "decision must declare at most one default branch" +
                                " (found ${defaults.size})",
                        targetId = decision.id,
                    )
            }
            when (decision.kind) {
                ContractGatewayKind.EXCLUSIVE -> {
                    val unconditional = decision.branches.filterIsInstance<UnconditionalBranch>()
                    unconditional.forEach { branch ->
                        issues +=
                            ContractValidationIssue(
                                code = ContractValidationCode.UNCONDITIONAL_BRANCH_ON_EXCLUSIVE,
                                severity = ContractIssueSeverity.ERROR,
                                message =
                                    "branch '${branch.id}' is UNCONDITIONAL but decision" +
                                        " '${decision.id}' is EXCLUSIVE — use a ConditionalBranch with a condition",
                                targetId = branch.id,
                            )
                    }
                    if (defaults.isNotEmpty() && decision.branches.none { it is ConditionalBranch }) {
                        issues +=
                            ContractValidationIssue(
                                code = ContractValidationCode.DECISION_DEFAULT_WITHOUT_CONDITIONAL,
                                severity = ContractIssueSeverity.ERROR,
                                message =
                                    "decision '${decision.id}' has a default branch but no conditional" +
                                        " branch — a decision cannot be 100% default",
                                targetId = decision.id,
                            )
                    }
                }

                ContractGatewayKind.PARALLEL -> {
                    defaults.forEach { branch ->
                        issues +=
                            ContractValidationIssue(
                                code = ContractValidationCode.DEFAULT_BRANCH_ON_PARALLEL,
                                severity = ContractIssueSeverity.ERROR,
                                message =
                                    "branch '${branch.id}' is DEFAULT but decision '${decision.id}'" +
                                        " is PARALLEL — default branches are valid only on EXCLUSIVE decisions",
                                targetId = branch.id,
                            )
                    }
                    val conditional = decision.branches.filterIsInstance<ConditionalBranch>()
                    conditional.forEach { branch ->
                        issues +=
                            ContractValidationIssue(
                                code = ContractValidationCode.CONDITIONAL_BRANCH_ON_PARALLEL,
                                severity = ContractIssueSeverity.ERROR,
                                message =
                                    "branch '${branch.id}' is CONDITIONAL but decision" +
                                        " '${decision.id}' is PARALLEL — all parallel branches fire unconditionally",
                                targetId = branch.id,
                            )
                    }
                }
            }
        }

        contract.activities.forEach { activity ->
            if (activity.sourceIds.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "activity '${activity.name}' must carry at least one source id",
                        targetId = activity.id,
                    )
            }
        }
        contract.decisions.forEach { decision ->
            if (decision.sourceIds.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "decision '${decision.question}' must carry at least one source id",
                        targetId = decision.id,
                    )
            }
        }
        contract.endStates.forEach { endState ->
            if (endState.sourceIds.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "end state '${endState.name}' must carry at least one source id",
                        targetId = endState.id,
                    )
            }
        }
        contract.assumptions.forEach { assumption ->
            if (assumption.sourceIds.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.ASSUMPTION_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "assumption '${assumption.text}' must carry at least one source id",
                        targetId = assumption.id,
                    )
            }
        }

        return ContractValidationReport(issues = issues.toList())
    }

    companion object {
        private const val MIN_ACTIVITIES = 2
        private const val MIN_DECISION_BRANCHES = 2
    }
}
