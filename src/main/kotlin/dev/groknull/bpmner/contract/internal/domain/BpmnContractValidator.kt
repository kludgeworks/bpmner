/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.ContractValidationIssue
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.EventGatewayBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import dev.groknull.bpmner.contract.dataInputIds
import dev.groknull.bpmner.contract.dataOutputIds
import dev.groknull.bpmner.contract.kindName
import org.springframework.stereotype.Component

@Component
@Suppress("TooManyFunctions") // per-contract-invariant private helpers keep validation rules isolated
internal class BpmnContractValidator {

    fun validate(contract: ProcessContract): ContractValidationReport {
        val issues =
            validateProcessIdentity(contract) +
                validateMinimumShape(contract) +
                validateUniqueIds(contract) +
                validateDecisions(contract) +
                validateIntermediateThrows(contract) +
                validateTraceability(contract) +
                validateActivityDataRefs(contract) +
                validateSubProcesses(contract)

        return ContractValidationReport(issues = issues)
    }

    // Every id in an activity's dataInputIds/dataOutputIds must reference a declared artifact
    // (data object/store), so the generator can wire a data association to an element that exists.
    private fun validateActivityDataRefs(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val artifactIds = contract.artifacts.map { it.id }.toSet()
        contract.activities.forEach { activity ->
            (activity.dataInputIds + activity.dataOutputIds)
                .filter { it.isNotBlank() && it !in artifactIds }
                .forEach { missingId ->
                    add(
                        errorIssue(
                            code = ContractValidationCode.DATA_REF_NOT_IN_ARTIFACTS,
                            message = "activity '${activity.id}' references data id '$missingId'" +
                                " that is not declared in the contract's artifacts",
                            targetId = activity.id,
                        ),
                    )
                }
        }
    }

    // Embedded-subprocess membership invariants. Subprocesses arrive appended to `activities` as
    // ContractActivity.SubProcess entries (see FlatContractMapper). Each member id must resolve to a
    // declared activity, a subprocess cannot contain itself, membership is exclusive (an activity
    // belongs to at most one subprocess), and a subprocess must contain at least one member.
    private fun validateSubProcesses(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val subProcesses = contract.activities.filterIsInstance<ContractActivity.SubProcess>()
        if (subProcesses.isEmpty()) return@buildList

        val activityIds = contract.activities.map { it.id }.toSet()
        val subProcessIds = subProcesses.map { it.id }.toSet()
        val membershipCount = mutableMapOf<String, Int>()

        subProcesses.forEach { subProcess ->
            if (subProcess.containedActivityIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.SUBPROCESS_EMPTY,
                        message = "subprocess '${subProcess.id}' must contain at least one member activity",
                        targetId = subProcess.id,
                    ),
                )
            }
            subProcess.containedActivityIds.forEach { memberId ->
                membershipCount[memberId] = (membershipCount[memberId] ?: 0) + 1
                when {
                    memberId == subProcess.id -> add(
                        errorIssue(
                            code = ContractValidationCode.SUBPROCESS_MEMBER_NOT_FOUND,
                            message = "subprocess '${subProcess.id}' lists itself as a member",
                            targetId = subProcess.id,
                        ),
                    )

                    // The member id resolves to another subprocess (it sits in `activities`), so the
                    // dangling-reference check below would not catch it. Nested subprocesses are out
                    // of scope for collapsed-only v1; surface it rather than passing silently.
                    memberId in subProcessIds -> add(
                        errorIssue(
                            code = ContractValidationCode.SUBPROCESS_NESTED_MEMBER,
                            message = "subprocess '${subProcess.id}' lists subprocess '$memberId' as a member" +
                                " — nested subprocesses are not supported",
                            targetId = subProcess.id,
                        ),
                    )

                    memberId !in activityIds -> add(
                        errorIssue(
                            code = ContractValidationCode.SUBPROCESS_MEMBER_NOT_FOUND,
                            message = "subprocess '${subProcess.id}' references member activity '$memberId'" +
                                " that is not declared in the contract's activities",
                            targetId = subProcess.id,
                        ),
                    )
                }
            }
        }

        membershipCount.filterValues { it > 1 }.forEach { (memberId, count) ->
            add(
                errorIssue(
                    code = ContractValidationCode.SUBPROCESS_MEMBER_SHARED,
                    message = "activity '$memberId' is claimed by $count subprocesses —" +
                        " an activity belongs to at most one",
                    targetId = memberId,
                ),
            )
        }
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

    private fun validateDecisions(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.decisions.forEach { decision ->
            addAll(validateDecisionBranchCount(decision))
            addAll(validateEventBranchPlacement(decision))

            val defaults = decision.branches.filterIsInstance<DefaultBranch>()
            addAll(validateDecisionDefaults(decision, defaults))

            when (decision.kind) {
                ContractGatewayKind.EXCLUSIVE -> addAll(
                    validateConditionalDecision(
                        decision,
                        defaults,
                        ContractValidationCode.UNCONDITIONAL_BRANCH_ON_EXCLUSIVE,
                        kindLabel = "EXCLUSIVE",
                    ),
                )

                ContractGatewayKind.INCLUSIVE -> addAll(
                    validateConditionalDecision(
                        decision,
                        defaults,
                        ContractValidationCode.UNCONDITIONAL_BRANCH_ON_INCLUSIVE,
                        kindLabel = "INCLUSIVE",
                    ),
                )

                ContractGatewayKind.PARALLEL -> addAll(validateParallelDecision(decision, defaults))

                // EVENT_BASED branch typing is enforced by validateEventBranchPlacement above; the
                // per-branch trigger fields are constrained by Jakarta validation on EventGatewayBranch.
                ContractGatewayKind.EVENT_BASED -> Unit
            }
        }
    }

    // An EVENT_BASED decision routes on whichever event fires first, so it carries EventGatewayBranch
    // branches exclusively; every other gateway kind routes on conditions and must carry none. The
    // condition/default/parallel cross-checks each filter by their own branch types and so cannot see
    // a stray EventGatewayBranch (nor an event decision holding the wrong branch), so the
    // correspondence is enforced here in both directions.
    private fun validateEventBranchPlacement(decision: ContractDecision): List<ContractValidationIssue> = buildList {
        if (decision.kind == ContractGatewayKind.EVENT_BASED) {
            decision.branches.filterNot { it is EventGatewayBranch }.forEach { branch ->
                add(
                    errorIssue(
                        code = ContractValidationCode.NON_EVENT_BRANCH_ON_EVENT_BASED,
                        message = "branch '${branch.id}' is ${branch.kindName} but decision '${decision.id}'" +
                            " is EVENT_BASED — use an EventGatewayBranch naming the triggering event",
                        targetId = branch.id,
                    ),
                )
            }
        } else {
            decision.branches.filterIsInstance<EventGatewayBranch>().forEach { branch ->
                add(
                    errorIssue(
                        code = ContractValidationCode.EVENT_BRANCH_ON_NON_EVENT_BASED,
                        message = "branch '${branch.id}' is EVENT_GATEWAY but decision '${decision.id}'" +
                            " is ${decision.kind} — event-gateway branches are valid only on EVENT_BASED decisions",
                        targetId = branch.id,
                    ),
                )
            }
        }
    }

    private fun validateUniqueIds(contract: ProcessContract): List<ContractValidationIssue> {
        val ids =
            contract.activities.map { IdEntry(it.id, "activity") } +
                contract.decisions.map { IdEntry(it.id, "decision") } +
                contract.decisions.flatMap { decision -> decision.branches.map { IdEntry(it.id, "branch") } } +
                contract.actors.map { IdEntry(it.id, "actor") } +
                contract.artifacts.map { IdEntry(it.id, "artifact") } +
                contract.endStates.map { IdEntry(it.id, "end state") } +
                contract.intermediateThrows.map { IdEntry(it.id, "intermediate throw") } +
                contract.assumptions.map { IdEntry(it.id, "assumption") }

        return ids
            .groupBy { it.id.trim() }
            .filterKeys { it.isNotEmpty() }
            .filterValues { it.size > 1 }
            .map { (id, duplicates) ->
                errorIssue(
                    code = ContractValidationCode.DUPLICATE_CONTRACT_ELEMENT_ID,
                    message = "contract element id '$id' is duplicated across " +
                        duplicates.joinToString { it.kind },
                    targetId = id,
                )
            }
    }

    // Cross-cutting branch-kind invariants over the sealed ContractBranch hierarchy.
    // Per-branch field constraints (non-blank label, non-blank condition on ConditionalBranch)
    // are enforced by Jakarta validation on the subtype constructors, not here.
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

    // EXCLUSIVE and INCLUSIVE share an identical validation body: each branch carries a
    // `condition`, unconditional branches are wrong (use PARALLEL instead), and a default
    // branch needs at least one conditional alongside it. The only difference is the
    // diagnostic code/kind label, which the caller passes in.
    private fun validateConditionalDecision(
        decision: ContractDecision,
        defaults: List<DefaultBranch>,
        unconditionalCode: ContractValidationCode,
        kindLabel: String,
    ): List<ContractValidationIssue> = buildList {
        val unconditional = decision.branches.filterIsInstance<UnconditionalBranch>()
        unconditional.forEach { branch ->
            add(
                errorIssue(
                    code = unconditionalCode,
                    message = "branch '${branch.id}' is UNCONDITIONAL but decision" +
                        " '${decision.id}' is $kindLabel — use a ConditionalBranch with a condition",
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
                        " is PARALLEL — default branches are valid only on EXCLUSIVE or INCLUSIVE decisions",
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

    private fun validateIntermediateThrows(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.intermediateThrows.forEach { intermediateThrow ->
            val invalidField = intermediateThrow.invalidPayloadField()
            if (invalidField != null) {
                add(
                    errorIssue(
                        code = ContractValidationCode.INVALID_CONTRACT_ITEM,
                        message = "intermediate throw '${intermediateThrow.id}' requires non-blank $invalidField",
                        targetId = intermediateThrow.id,
                    ),
                )
            }
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
        contract.intermediateThrows.forEach { intermediateThrow ->
            if (intermediateThrow.sourceIds.isEmpty()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE,
                        message = "intermediate throw '${intermediateThrow.name}' must carry at least one source id",
                        targetId = intermediateThrow.id,
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

private data class IdEntry(val id: String, val kind: String)

private fun ContractIntermediateThrow.invalidPayloadField(): String? = when (this) {
    is ContractIntermediateThrow.Message -> "messageName".takeIf { messageName.isBlank() }
    is ContractIntermediateThrow.Signal -> "signalName".takeIf { signalName.isBlank() }
    is ContractIntermediateThrow.Escalation -> "escalationCode".takeIf { escalationCode.isBlank() }
}
