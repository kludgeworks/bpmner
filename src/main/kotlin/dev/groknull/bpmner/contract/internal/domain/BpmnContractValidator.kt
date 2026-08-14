/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractFlow
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
import dev.groknull.bpmner.contract.boundaryEvents
import dev.groknull.bpmner.contract.kindName
import org.springframework.stereotype.Component

@Component
// Per-contract-invariant private helpers keep validation rules isolated (13 V-rules plus the
// pre-existing structural checks); this is a cohesive single-responsibility validator, not a
// class that wants splitting — same precedent as BpmnContractValidatorTest.kt.
@Suppress("TooManyFunctions", "LargeClass")
internal class BpmnContractValidator {

    fun validate(contract: ProcessContract): ContractValidationReport {
        val issues =
            validateProcessIdentity(contract) +
                validateMinimumShape(contract) +
                validateUniqueIds(contract) +
                validateDecisions(contract) +
                validateIntermediateThrows(contract) +
                validateTraceability(contract) +
                validateSubProcesses(contract) +
                validateCallActivities(contract) +
                validateFlows(contract)

        return ContractValidationReport(issues = issues)
    }

    // V1-V13 (ADR-696-1): total-topology validation over `flows`, decidable and total — no
    // reachability heuristic, no "probably meant". Replaces the deleted branch/boundary-event
    // target field and its validateReferences checker; V1 is its strictly stronger successor.
    private fun validateFlows(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        addAll(validateFlowEndpoints(contract)) // V1, V2
        addAll(validateDegreeConstraints(contract)) // V3, V4, V5, V6
        addAll(validateReachability(contract)) // V7, V8
        addAll(validateBranchRealization(contract)) // V9
        addAll(validateFlowKindMatchesSource(contract)) // V10
        addAll(validateSubprocessBoundary(contract)) // V11
        addAll(validateSubprocessConnectivity(contract)) // V12
        addAll(validateNoDuplicateFlows(contract)) // V13
    }

    // V1: every flow endpoint resolves to a declared id. V2: a flow's `from` and `to` differ.
    private fun validateFlowEndpoints(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val ids = contract.flowAddressableIds()
        contract.flows.forEach { flow ->
            if (flow.from !in ids) {
                add(
                    errorIssue(
                        code = ContractValidationCode.FLOW_ENDPOINT_NOT_FOUND,
                        message = "flow from '${flow.from}' does not resolve to any declared element",
                        targetId = flow.from,
                    ),
                )
            }
            if (flow.to !in ids) {
                add(
                    errorIssue(
                        code = ContractValidationCode.FLOW_ENDPOINT_NOT_FOUND,
                        message = "flow to '${flow.to}' does not resolve to any declared element",
                        targetId = flow.to,
                    ),
                )
            }
            if (flow.from == flow.to) {
                add(
                    errorIssue(
                        code = ContractValidationCode.FLOW_SELF_LOOP,
                        message = "flow from '${flow.from}' to itself is not allowed",
                        targetId = flow.from,
                    ),
                )
            }
        }
    }

    // V3 (start), V4 (end states), V5 (boundary events), V6 (every other element — excluding
    // subprocess members, whose degree is governed by V11/V12 instead of the flat-graph rules).
    // Split into one function per rule: each stays short and simple; `incoming`/`outgoing` are
    // computed once here and threaded through.
    private fun validateDegreeConstraints(contract: ProcessContract): List<ContractValidationIssue> {
        val incoming = contract.flows.groupingBy { it.to }.eachCount()
        val outgoing = contract.flows.groupingBy { it.from }.eachCount()
        return validateStartDegree(contract, incoming, outgoing) +
            validateEndStateDegree(contract, incoming, outgoing) +
            validateBoundaryEventDegree(contract, incoming, outgoing) +
            validateOtherElementDegree(contract, incoming, outgoing)
    }

    private fun validateStartDegree(
        contract: ProcessContract,
        incoming: Map<String, Int>,
        outgoing: Map<String, Int>,
    ): List<ContractValidationIssue> = buildList {
        val startIncoming = incoming[contract.start.id] ?: 0
        if (startIncoming > 0) {
            add(
                errorIssue(
                    code = ContractValidationCode.START_HAS_INCOMING_FLOW,
                    message = "start '${contract.start.id}' must have no incoming flow (found $startIncoming)",
                    targetId = contract.start.id,
                ),
            )
        }
        val startOutgoing = outgoing[contract.start.id] ?: 0
        if (startOutgoing != 1) {
            add(
                errorIssue(
                    code = ContractValidationCode.START_OUTGOING_COUNT_WRONG,
                    message = "start '${contract.start.id}' must have exactly one outgoing flow (found $startOutgoing)",
                    targetId = contract.start.id,
                ),
            )
        }
    }

    private fun validateEndStateDegree(
        contract: ProcessContract,
        incoming: Map<String, Int>,
        outgoing: Map<String, Int>,
    ): List<ContractValidationIssue> = buildList {
        contract.endStates.forEach { endState ->
            if ((incoming[endState.id] ?: 0) < 1) {
                add(
                    errorIssue(
                        code = ContractValidationCode.END_STATE_MISSING_INCOMING_FLOW,
                        message = "end state '${endState.id}' must have at least one incoming flow",
                        targetId = endState.id,
                    ),
                )
            }
            val endOutgoing = outgoing[endState.id] ?: 0
            if (endOutgoing > 0) {
                add(
                    errorIssue(
                        code = ContractValidationCode.END_STATE_HAS_OUTGOING_FLOW,
                        message = "end state '${endState.id}' must have no outgoing flow (found $endOutgoing)",
                        targetId = endState.id,
                    ),
                )
            }
        }
    }

    private fun validateBoundaryEventDegree(
        contract: ProcessContract,
        incoming: Map<String, Int>,
        outgoing: Map<String, Int>,
    ): List<ContractValidationIssue> = buildList {
        contract.activities.flatMap { it.boundaryEvents }.forEach { boundaryEvent ->
            val beIncoming = incoming[boundaryEvent.id] ?: 0
            if (beIncoming > 0) {
                add(
                    errorIssue(
                        code = ContractValidationCode.BOUNDARY_EVENT_HAS_INCOMING_FLOW,
                        message = "boundary event '${boundaryEvent.id}' must have no incoming flow (found $beIncoming)",
                        targetId = boundaryEvent.id,
                    ),
                )
            }
            val beOutgoing = outgoing[boundaryEvent.id] ?: 0
            if (beOutgoing != 1) {
                add(
                    errorIssue(
                        code = ContractValidationCode.BOUNDARY_EVENT_OUTGOING_COUNT_WRONG,
                        message = "boundary event '${boundaryEvent.id}' must have exactly one outgoing flow (found $beOutgoing)",
                        targetId = boundaryEvent.id,
                    ),
                )
            }
        }
    }

    private fun validateOtherElementDegree(
        contract: ProcessContract,
        incoming: Map<String, Int>,
        outgoing: Map<String, Int>,
    ): List<ContractValidationIssue> = buildList {
        val excluded = contract.subprocessMemberIds() + contract.start.id +
            contract.endStates.map { it.id } + contract.activities.flatMap { it.boundaryEvents }.map { it.id }
        (contract.flowAddressableIds() - excluded).forEach { id ->
            if ((incoming[id] ?: 0) < 1) {
                add(
                    errorIssue(
                        code = ContractValidationCode.ELEMENT_MISSING_INCOMING_FLOW,
                        message = "element '$id' is declared but nothing flows into it",
                        targetId = id,
                    ),
                )
            }
            if ((outgoing[id] ?: 0) < 1) {
                add(
                    errorIssue(
                        code = ContractValidationCode.ELEMENT_MISSING_OUTGOING_FLOW,
                        message = "element '$id' is declared but nothing flows out of it",
                        targetId = id,
                    ),
                )
            }
        }
    }

    // V7 (forward reachability from start) and V8 (reverse reachability to some end state).
    // Subprocess members are excluded: they join the outer flow through the subprocess's own id
    // (V11), so their reachability is V12's job — a member with no internal predecessor is not
    // "unreachable from start", it is the subprocess's entry point.
    private fun validateReachability(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val checkable = contract.flowAddressableIds() - contract.subprocessMemberIds()
        val outAdjacency = contract.flows.groupBy { it.from }.mapValues { (_, v) -> v.map { it.to } }
        val inAdjacency = contract.flows.groupBy { it.to }.mapValues { (_, v) -> v.map { it.from } }

        val reachableFromStart = bfs(contract.start.id, outAdjacency)
        (checkable - reachableFromStart).forEach { id ->
            add(
                errorIssue(
                    code = ContractValidationCode.ELEMENT_UNREACHABLE_FROM_START,
                    message = "element '$id' is declared but is not reachable from start",
                    targetId = id,
                ),
            )
        }

        val endIds = contract.endStates.map { it.id }.toSet()
        val reachesAnEnd = endIds.flatMap { bfs(it, inAdjacency) }.toSet() + endIds
        (checkable - reachesAnEnd).forEach { id ->
            add(
                errorIssue(
                    code = ContractValidationCode.ELEMENT_CANNOT_REACH_END_STATE,
                    message = "element '$id' is declared but does not reach any end state",
                    targetId = id,
                ),
            )
        }
    }

    // V9: every Branch flow's `branchId` names a branch of the decision at its `from`; every
    // branch of every decision is realised by at least one Branch flow (V13 catches "more than
    // once", so together they enforce "exactly one").
    private fun validateBranchRealization(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val branchFlows = contract.flows.filterIsInstance<ContractFlow.Branch>()
        val flowsByBranchId = branchFlows.groupBy { it.branchId }
        val decisionsById = contract.decisions.associateBy { it.id }

        contract.decisions.forEach { decision ->
            decision.branches.forEach { branch ->
                if (flowsByBranchId[branch.id].orEmpty().isEmpty()) {
                    add(
                        errorIssue(
                            code = ContractValidationCode.DECISION_BRANCH_NOT_REALIZED,
                            message = "branch '${branch.id}' of decision '${decision.id}' is not realised by any flow",
                            targetId = branch.id,
                        ),
                    )
                }
            }
        }

        branchFlows.forEach { flow ->
            val decision = decisionsById[flow.from] ?: return@forEach // V10 reports a non-decision source
            if (decision.branches.none { it.id == flow.branchId }) {
                add(
                    errorIssue(
                        code = ContractValidationCode.FLOW_BRANCH_ID_UNKNOWN,
                        message = "flow from '${flow.from}' names branchId '${flow.branchId}'," +
                            " which is not a branch of that decision",
                        targetId = flow.from,
                    ),
                )
            }
        }
    }

    // V10: a decision's outgoing flows are all Branch; every other element's are all Sequence.
    private fun validateFlowKindMatchesSource(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val decisionIds = contract.decisions.map { it.id }.toSet()
        contract.flows.forEach { flow ->
            when (flow) {
                is ContractFlow.Sequence -> if (flow.from in decisionIds) {
                    add(
                        errorIssue(
                            code = ContractValidationCode.SEQUENCE_FLOW_FROM_DECISION,
                            message = "flow from decision '${flow.from}' must be a Branch flow naming a branchId," +
                                " not a Sequence flow",
                            targetId = flow.from,
                        ),
                    )
                }

                is ContractFlow.Branch -> if (flow.from !in decisionIds) {
                    add(
                        errorIssue(
                            code = ContractValidationCode.BRANCH_FLOW_FROM_NON_DECISION,
                            message = "flow from '${flow.from}' is a Branch flow, but '${flow.from}' is not a decision",
                            targetId = flow.from,
                        ),
                    )
                }
            }
        }
    }

    // V11: a subprocess joins the outer flow through its own id — a flow with exactly one
    // endpoint among a subprocess's containedActivityIds reaches into or out of it directly.
    private fun validateSubprocessBoundary(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val memberIds = contract.subprocessMemberIds()
        if (memberIds.isEmpty()) return@buildList
        contract.flows.forEach { flow ->
            if ((flow.from in memberIds) != (flow.to in memberIds)) {
                add(
                    errorIssue(
                        code = ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY,
                        message = "flow from '${flow.from}' to '${flow.to}' crosses a subprocess boundary" +
                            " through a member id directly — route through the subprocess's own id instead",
                        targetId = flow.from,
                    ),
                )
            }
        }
    }

    // V12: subprocess interior is connected — every member is reachable, via flows between
    // members, from some member with no internal predecessor (the subprocess's entry point(s)).
    private fun validateSubprocessConnectivity(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        val subProcesses = contract.activities.filterIsInstance<ContractActivity.SubProcess>()
        subProcesses.forEach { subProcess ->
            val members = subProcess.containedActivityIds.toSet()
            val interior = contract.flows.filter { it.from in members && it.to in members }
            val interiorAdjacency = interior.groupBy { it.from }.mapValues { (_, v) -> v.map { it.to } }
            val hasInternalPredecessor = interior.map { it.to }.toSet()
            val entryPoints = members - hasInternalPredecessor
            val reachable = entryPoints.flatMap { bfs(it, interiorAdjacency) }.toSet()
            (members - reachable).forEach { unreached ->
                add(
                    errorIssue(
                        code = ContractValidationCode.SUBPROCESS_MEMBER_UNREACHABLE,
                        message = "member '$unreached' of subprocess '${subProcess.id}' is not reachable from" +
                            " any subprocess entry point",
                        targetId = unreached,
                    ),
                )
            }
        }
    }

    // V13: no duplicate edges. Per-kind uniqueness — (from, to) for Sequence, branchId for
    // Branch — not plain (from, to) across all flows: a decision with two branches to the same
    // target is legitimate.
    private fun validateNoDuplicateFlows(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.flows.filterIsInstance<ContractFlow.Sequence>()
            .groupBy { it.from to it.to }
            .filterValues { it.size > 1 }
            .forEach { (pair, dupes) ->
                add(
                    errorIssue(
                        code = ContractValidationCode.DUPLICATE_FLOW,
                        message = "${dupes.size} identical flows from '${pair.first}' to '${pair.second}'",
                        targetId = pair.first,
                    ),
                )
            }

        contract.flows.filterIsInstance<ContractFlow.Branch>()
            .groupBy { it.branchId }
            .filterValues { it.size > 1 }
            .forEach { (branchId, dupes) ->
                add(
                    errorIssue(
                        code = ContractValidationCode.DUPLICATE_BRANCH_REALIZATION,
                        message = "branch '$branchId' is realised by ${dupes.size} flows; exactly one is required",
                        targetId = branchId,
                    ),
                )
            }
    }

    private fun bfs(
        startNode: String,
        adjacency: Map<String, List<String>>,
    ): Set<String> {
        val visited = mutableSetOf(startNode)
        val queue = ArrayDeque(listOf(startNode))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (next in adjacency[node].orEmpty()) {
                if (visited.add(next)) queue.add(next)
            }
        }
        return visited
    }

    // A call activity delegates to a separately-defined process named by `calledElement`. That
    // reference is the activity's whole payload, so it must be present (the called process is
    // resolved externally, so we do not require it to appear in this contract).
    private fun validateCallActivities(contract: ProcessContract): List<ContractValidationIssue> = buildList {
        contract.activities.filterIsInstance<ContractActivity.CallActivity>().forEach { callActivity ->
            if (callActivity.calledElement.isBlank()) {
                add(
                    errorIssue(
                        code = ContractValidationCode.CALL_ACTIVITY_MISSING_TARGET,
                        message = "call activity '${callActivity.id}' must name the process it invokes (calledElement)",
                        targetId = callActivity.id,
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
        // memberId -> the distinct subprocess ids that claim it. Tracking distinct owners (rather than
        // raw occurrences) means a member listed twice within one subprocess isn't misreported as shared.
        val claimants = mutableMapOf<String, MutableSet<String>>()

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
                claimants.getOrPut(memberId) { mutableSetOf() }.add(subProcess.id)
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

        claimants.filterValues { it.size > 1 }.forEach { (memberId, owners) ->
            add(
                errorIssue(
                    code = ContractValidationCode.SUBPROCESS_MEMBER_SHARED,
                    message = "activity '$memberId' is claimed by ${owners.size} subprocesses —" +
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
        if (contract.start.trigger.description.isBlank()) {
            add(
                errorIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    message = "process trigger must not be blank",
                    targetId = contract.id,
                ),
            )
        } else if (contract.start.sourceIds.isEmpty()) {
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

// Every id a flow may name as an endpoint: start, activities (including subprocess members —
// they are valid flow endpoints for interior flows; V11 forbids only outer flows crossing the
// boundary through them), decisions, end states, intermediate throws, and boundary events.
private fun ProcessContract.flowAddressableIds(): Set<String> = buildSet {
    add(start.id)
    activities.forEach { add(it.id) }
    decisions.forEach { add(it.id) }
    endStates.forEach { add(it.id) }
    intermediateThrows.forEach { add(it.id) }
    activities.forEach { activity -> activity.boundaryEvents.forEach { add(it.id) } }
}

private fun ProcessContract.subprocessMemberIds(): Set<String> =
    activities.filterIsInstance<ContractActivity.SubProcess>()
        .flatMap { it.containedActivityIds }
        .toSet()

private fun ContractIntermediateThrow.invalidPayloadField(): String? = when (this) {
    is ContractIntermediateThrow.Message -> "messageName".takeIf { messageName.isBlank() }
    is ContractIntermediateThrow.Signal -> "signalName".takeIf { signalName.isBlank() }
    is ContractIntermediateThrow.Escalation -> "escalationCode".takeIf { escalationCode.isBlank() }
}
