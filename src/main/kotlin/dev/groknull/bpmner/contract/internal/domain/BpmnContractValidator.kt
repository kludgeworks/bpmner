package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.ContractValidationIssue
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
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
        } else if (contract.triggerTraceLinks.isEmpty()) {
            issues +=
                ContractValidationIssue(
                    code = ContractValidationCode.TRIGGER_WITHOUT_TRACE,
                    severity = ContractIssueSeverity.ERROR,
                    message = "process trigger must carry at least one trace link",
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
            decision.branches.forEach { branch ->
                if (branch.label.isBlank() && branch.condition.isNullOrBlank()) {
                    issues +=
                        ContractValidationIssue(
                            code = ContractValidationCode.BRANCH_WITHOUT_CONDITION_OR_LABEL,
                            severity = ContractIssueSeverity.ERROR,
                            message = "branch must have a non-blank label or condition",
                            targetId = branch.id,
                        )
                }
            }
        }

        contract.activities.forEach { activity ->
            if (activity.traceLinks.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "activity '${activity.name}' must carry at least one trace link",
                        targetId = activity.id,
                    )
            }
        }
        contract.decisions.forEach { decision ->
            if (decision.traceLinks.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "decision '${decision.question}' must carry at least one trace link",
                        targetId = decision.id,
                    )
            }
        }
        contract.endStates.forEach { endState ->
            if (endState.traceLinks.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "end state '${endState.name}' must carry at least one trace link",
                        targetId = endState.id,
                    )
            }
        }
        contract.assumptions.forEach { assumption ->
            if (assumption.traceLinks.isEmpty()) {
                issues +=
                    ContractValidationIssue(
                        code = ContractValidationCode.ASSUMPTION_WITHOUT_TRACE,
                        severity = ContractIssueSeverity.ERROR,
                        message = "assumption '${assumption.text}' must carry at least one trace link",
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
