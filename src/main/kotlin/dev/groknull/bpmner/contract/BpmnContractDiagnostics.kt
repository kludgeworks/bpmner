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

    /** A call activity declares no `calledElement`; it must name the process it invokes. */
    CALL_ACTIVITY_MISSING_TARGET,

    // V1-V13: total-topology validation over ProcessContract.flows.

    /** V1: a flow's `from` or `to` does not resolve to any declared element. */
    FLOW_ENDPOINT_NOT_FOUND,

    /** V2: a flow's `from` and `to` are the same element. */
    FLOW_SELF_LOOP,

    /** V3: the start has an incoming flow (it must have none). */
    START_HAS_INCOMING_FLOW,

    /** V3: the start does not have exactly one outgoing flow. */
    START_OUTGOING_COUNT_WRONG,

    /** V4: an end state has no incoming flow (it must have at least one). */
    END_STATE_MISSING_INCOMING_FLOW,

    /** V4: an end state has an outgoing flow (it must have none). */
    END_STATE_HAS_OUTGOING_FLOW,

    /** V5: a boundary event has an incoming flow (it must have none). */
    BOUNDARY_EVENT_HAS_INCOMING_FLOW,

    /** V5: a boundary event does not have exactly one outgoing flow. */
    BOUNDARY_EVENT_OUTGOING_COUNT_WRONG,

    /** V6: a non-start/end/boundary/subprocess-member element has no incoming flow. */
    ELEMENT_MISSING_INCOMING_FLOW,

    /** V6: a non-start/end/boundary/subprocess-member element has no outgoing flow. */
    ELEMENT_MISSING_OUTGOING_FLOW,

    /** V7: a declared element (other than a subprocess member) is not reachable from start. */
    ELEMENT_UNREACHABLE_FROM_START,

    /** V8: a declared element (other than a subprocess member) cannot reach any end state. */
    ELEMENT_CANNOT_REACH_END_STATE,

    /** V9: a decision's branch is not realised by any [dev.groknull.bpmner.contract.ContractFlow.Branch]. */
    DECISION_BRANCH_NOT_REALIZED,

    /** V9: a [dev.groknull.bpmner.contract.ContractFlow.Branch]'s `branchId` does not name a branch of the decision at `from`. */
    FLOW_BRANCH_ID_UNKNOWN,

    /** V10: a decision's outgoing flow is a Sequence flow; decisions route only via Branch flows. */
    SEQUENCE_FLOW_FROM_DECISION,

    /** V10: a non-decision's outgoing flow is a Branch flow; only decisions route via Branch flows. */
    BRANCH_FLOW_FROM_NON_DECISION,

    /** V11: a flow has exactly one endpoint among a subprocess's `containedActivityIds`. */
    FLOW_CROSSES_SUBPROCESS_BOUNDARY,

    /** V12: a subprocess member is not reachable from any member with no internal predecessor. */
    SUBPROCESS_MEMBER_UNREACHABLE,

    /** V13: more than one identical `(from, to)` Sequence flow. */
    DUPLICATE_FLOW,

    /** V13: more than one Branch flow realises the same `branchId`. */
    DUPLICATE_BRANCH_REALIZATION,
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

@ConsistentCopyVisibility
data class ValidatedProcessContract private constructor(
    val contract: ProcessContract,
    val report: ContractValidationReport,
) {
    companion object {
        /** Returns `null` when [report] carries an ERROR-severity issue. */
        fun of(
            contract: ProcessContract,
            report: ContractValidationReport,
        ): ValidatedProcessContract? = if (report.isValid) ValidatedProcessContract(contract, report) else null
    }
}

fun ContractValidationIssue.format(): String = buildString {
    append("code=${code.name.lowercase()}")
    append(", severity=${severity.name.lowercase()}")
    targetId?.let { append(", targetId=$it") }
    if (evidenceIds.isNotEmpty()) append(", evidenceIds=${evidenceIds.joinToString(",")}")
    append(": $message")
}
