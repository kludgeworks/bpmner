/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractDecision
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

    fun validate(contract: ProcessContract): ContractValidationReport {
        val issues = buildList {
            addAll(validateProcessIdentity(contract))
            addAll(validateMinimumShape(contract))
            addAll(validateDecisions(contract))
            addAll(validateTraceability(contract))
        }
        return ContractValidationReport(issues = issues)
    }

    private fun validateProcessIdentity(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        if (contract.processName.isBlank()) {
            add(
                errorIssue(
                    code = ContractValidationCode.MISSING_PROCESS_NAME,
                    message = "process name must not be blank",
                    targetId = contract.id,
                ),
            )
        }
        if (contract.trigger.isBlank()) {
            add(
                errorIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    message = "process trigger must not be blank",
                    targetId = contract.id,
                ),
            )
        } else if (contract.triggerSourceIds.isEmpty()) {
            add(
                errorIssue(
                    code = ContractValidationCode.TRIGGER_WITHOUT_TRACE,
                    message = "process trigger must carry at least one source id",
                    targetId = contract.id,
                ),
            )
        }
    }

    private fun validateMinimumShape(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        if (contract.endStates.isEmpty()) {
            add(
                errorIssue(
                    code = ContractValidationCode.NO_END_STATE,
                    message = "process contract must declare at least one end state",
                    targetId = contract.id,
                ),
            )
        }
        if (contract.activities.size < MIN_ACTIVITIES) {
            add(
                errorIssue(
                    code = ContractValidationCode.INSUFFICIENT_ACTIVITIES,
                    message = "process contract must declare at least $MIN_ACTIVITIES activities" +
                        " (found ${contract.activities.size})",
                    targetId = contract.id,
                ),
            )
        }
    }

    // Cross-cutting branch-kind invariants over the sealed ContractBranch hierarchy.
    // Per-branch field constraints (non-blank label, non-blank condition on ConditionalBranch)
    // are enforced by Jakarta validation on the subtype constructors, not here.
    private fun validateDecisions(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.decisions.forEach { decision ->
            addAll(validateDecisionBranchCount(decision))

            val defaults = decision.branches.filterIsInstance<DefaultBranch>()
            addAll(validateDecisionDefaults(decision, defaults))

            when (decision.kind) {
                ContractGatewayKind.EXCLUSIVE -> addAll(validateExclusiveDecision(decision, defaults))
                ContractGatewayKind.PARALLEL -> addAll(validateParallelDecision(decision, defaults))
            }
        }
    }

    private fun validateDecisionBranchCount(decision: ContractDecision): List<ContractValidationIssue> = buildList {
        if (decision.branches.size < MIN_DECISION_BRANCHES) {
            add(
                errorIssue(
                    code = ContractValidationCode.DECISION_BRANCH_TOO_FEW,
                    message = "decision must declare at least $MIN_DECISION_BRANCHES branches" +
                        " (found ${decision.branches.size})",
                    targetId = decision.id,
                ),
            )
        }
    }

    private fun validateDecisionDefaults(
        decision: ContractDecision,
        defaults: List<DefaultBranch>,
    ): List<ContractValidationIssue> = buildList {
        if (defaults.size > 1) {
            add(
                errorIssue(
                    code = ContractValidationCode.DECISION_MULTIPLE_DEFAULTS,
                    message = "decision must declare at most one default branch" +
                        " (found ${defaults.size})",
                    targetId = decision.id,
                ),
            )
        }
    }

    private fun validateExclusiveDecision(
        decision: ContractDecision,
        defaults: List<DefaultBranch>,
    ): List<ContractValidationIssue> = buildList {
        val unconditional = decision.branches.filterIsInstance<UnconditionalBranch>()
        unconditional.forEach { branch ->
            add(
                errorIssue(
                    code = ContractValidationCode.UNCONDITIONAL_BRANCH_ON_EXCLUSIVE,
                    message = "branch '${branch.id}' is UNCONDITIONAL but decision" +
                        " '${decision.id}' is EXCLUSIVE — use a ConditionalBranch with a condition",
                    targetId = branch.id,
                ),
            )
        }
        if (defaults.isNotEmpty() && decision.branches.none { it is ConditionalBranch }) {
            add(
                errorIssue(
                    code = ContractValidationCode.DECISION_DEFAULT_WITHOUT_CONDITIONAL,
                    message = "decision '${decision.id}' has a default branch but no conditional" +
                        " branch — a decision cannot be 100% default",
                    targetId = decision.id,
                ),
            )
        }
    }

    private fun validateParallelDecision(
        decision: ContractDecision,
        defaults: List<DefaultBranch>,
    ): List<ContractValidationIssue> = buildList {
        defaults.forEach { branch ->
            add(
                errorIssue(
                    code = ContractValidationCode.DEFAULT_BRANCH_ON_PARALLEL,
                    message = "branch '${branch.id}' is DEFAULT but decision '${decision.id}'" +
                        " is PARALLEL — default branches are valid only on EXCLUSIVE decisions",
                    targetId = branch.id,
                ),
            )
        }
        val conditional = decision.branches.filterIsInstance<ConditionalBranch>()
        conditional.forEach { branch ->
            add(
                errorIssue(
                    code = ContractValidationCode.CONDITIONAL_BRANCH_ON_PARALLEL,
                    message = "branch '${branch.id}' is CONDITIONAL but decision" +
                        " '${decision.id}' is PARALLEL — all parallel branches fire unconditionally",
                    targetId = branch.id,
                ),
            )
        }
    }

    private fun validateTraceability(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.activities.forEach { activity ->
            if (activity.sourceIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        message = "activity '${activity.name}' must carry at least one source id",
                        targetId = activity.id,
                    ),
                )
            }
        }
        contract.decisions.forEach { decision ->
            if (decision.sourceIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        message = "decision '${decision.question}' must carry at least one source id",
                        targetId = decision.id,
                    ),
                )
            }
        }
        contract.endStates.forEach { endState ->
            if (endState.sourceIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        message = "end state '${endState.name}' must carry at least one source id",
                        targetId = endState.id,
                    ),
                )
            }
        }
        contract.assumptions.forEach { assumption ->
            if (assumption.sourceIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.ASSUMPTION_WITHOUT_TRACE,
                        message = "assumption '${assumption.text}' must carry at least one source id",
                        targetId = assumption.id,
                    ),
                )
            }
        }
    }

    private fun errorIssue(
        code: ContractValidationCode,
        message: String,
        targetId: String,
    ): ContractValidationIssue = ContractValidationIssue(
        code = code,
        severity = ContractIssueSeverity.ERROR,
        message = message,
        targetId = targetId,
    )

    companion object {
        private const val MIN_ACTIVITIES = 2
        private const val MIN_DECISION_BRANCHES = 2
    }
}
