/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.fasterxml.jackson.annotation.JsonClassDescription

enum class ContractIssueSeverity {
    ERROR,
    WARNING,
}

enum class ContractValidationCode {
    MISSING_PROCESS_NAME,
    MISSING_TRIGGER,
    TRIGGER_WITHOUT_TRACE,
    NO_END_STATE,
    INSUFFICIENT_ACTIVITIES,
    DECISION_BRANCH_TOO_FEW,
    BRANCH_WITHOUT_CONDITION_OR_LABEL,

    /** A decision has more than one DefaultBranch. At most one is allowed. */
    DECISION_MULTIPLE_DEFAULTS,

    /** A DefaultBranch appeared under a PARALLEL decision (defaults are EXCLUSIVE-only). */
    DEFAULT_BRANCH_ON_PARALLEL,

    /** An EXCLUSIVE decision has a DefaultBranch but no ConditionalBranch alongside it. */
    DECISION_DEFAULT_WITHOUT_CONDITIONAL,

    /** A ConditionalBranch appeared under a PARALLEL decision (parallel branches are unconditional). */
    CONDITIONAL_BRANCH_ON_PARALLEL,

    /** An UnconditionalBranch appeared under an EXCLUSIVE decision (exclusive branches are conditional). */
    UNCONDITIONAL_BRANCH_ON_EXCLUSIVE,

    /** An UnconditionalBranch appeared under an INCLUSIVE decision (inclusive branches are conditional). */
    UNCONDITIONAL_BRANCH_ON_INCLUSIVE,

    ASSUMPTION_WITHOUT_TRACE,
    CONTRACT_ITEM_WITHOUT_TRACE,
}

@JsonClassDescription("Structural validation issue raised against an extracted ProcessContract")
data class ContractValidationIssue(
    val code: ContractValidationCode,
    val severity: ContractIssueSeverity,
    val message: String,
    val targetId: String? = null,
    val evidenceIds: List<String> = emptyList(),
)

data class ContractValidationReport(
    val issues: List<ContractValidationIssue>,
) {
    val isValid: Boolean = issues.none { it.severity == ContractIssueSeverity.ERROR }
}

data class ValidatedProcessContract(
    val contract: ProcessContract,
    val report: ContractValidationReport,
) {
    val isValid: Boolean = report.isValid
}

fun ContractValidationIssue.format(): String = buildString {
    append("code=${code.name.lowercase()}")
    append(", severity=${severity.name.lowercase()}")
    targetId?.let { append(", targetId=$it") }
    if (evidenceIds.isNotEmpty()) append(", evidenceIds=${evidenceIds.joinToString(",")}")
    append(": $message")
}
