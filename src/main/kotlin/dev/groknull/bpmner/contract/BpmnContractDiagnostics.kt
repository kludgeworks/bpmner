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

    /** A DefaultBranch appeared under a PARALLEL decision (defaults are EXCLUSIVE- or INCLUSIVE-only). */
    DEFAULT_BRANCH_ON_PARALLEL,

    /** An EXCLUSIVE decision has a DefaultBranch but no ConditionalBranch alongside it. */
    DECISION_DEFAULT_WITHOUT_CONDITIONAL,

    /** A ConditionalBranch appeared under a PARALLEL decision (parallel branches are unconditional). */
    CONDITIONAL_BRANCH_ON_PARALLEL,

    /** An UnconditionalBranch appeared under an EXCLUSIVE decision (exclusive branches are conditional). */
    UNCONDITIONAL_BRANCH_ON_EXCLUSIVE,

    /** An UnconditionalBranch appeared under an INCLUSIVE decision (inclusive branches are conditional). */
    UNCONDITIONAL_BRANCH_ON_INCLUSIVE,

    /** A non-EventGatewayBranch appeared under an EVENT_BASED decision (event decisions route on triggers). */
    NON_EVENT_BRANCH_ON_EVENT_BASED,

    /** An EventGatewayBranch appeared under a non-EVENT_BASED decision (event branches are EVENT_BASED-only). */
    EVENT_BRANCH_ON_NON_EVENT_BASED,

    ASSUMPTION_WITHOUT_TRACE,
    CONTRACT_ITEM_WITHOUT_TRACE,
    DUPLICATE_CONTRACT_ELEMENT_ID,
    INVALID_CONTRACT_ITEM,

    /** An activity's dataInputIds/dataOutputIds references an id not present in the contract's artifacts. */
    DATA_REF_NOT_IN_ARTIFACTS,

    /** A subprocess declares no member activities. An embedded subprocess must contain at least one. */
    SUBPROCESS_EMPTY,

    /** A subprocess's member id does not resolve to a declared activity (or names the subprocess itself). */
    SUBPROCESS_MEMBER_NOT_FOUND,

    /** A subprocess names another subprocess as a member; nested subprocesses are not supported (v1). */
    SUBPROCESS_NESTED_MEMBER,

    /** An activity is claimed as a member by more than one subprocess; membership is exclusive. */
    SUBPROCESS_MEMBER_SHARED,

    /** An event subprocess declares no handler activities. It must contain at least one. */
    EVENT_SUBPROCESS_EMPTY,

    /** An event subprocess's handler member id does not resolve to a declared activity. */
    EVENT_SUBPROCESS_MEMBER_NOT_FOUND,

    /** An activity is claimed as a handler by more than one event subprocess; membership is exclusive. */
    EVENT_SUBPROCESS_MEMBER_SHARED,

    /** An event subprocess has an ERROR trigger but is non-interrupting; error handlers must interrupt. */
    EVENT_SUBPROCESS_ERROR_NOT_INTERRUPTING,

    /**
     * An activity is claimed both by an embedded subprocess and an event subprocess. Membership is
     * exclusive across container kinds — a BPMN node has a single parent — so this is surfaced at
     * contract time rather than left to the fidelity checker.
     */
    SUBPROCESS_MEMBER_CROSS_CLAIMED,
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
