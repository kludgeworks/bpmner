/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnGateway
import dev.groknull.bpmner.api.BpmnTask
import dev.groknull.bpmner.api.typeName
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.iteration
import dev.groknull.bpmner.contract.kindName
import dev.groknull.bpmner.contract.loop
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEventBasedGateway
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnInclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.isSemanticallyTransparent
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
 * 2. [BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH] — the gateway exists but its kind
 *    (exclusive vs parallel) does not match the decision's declared [ContractGatewayKind].
 * 3. [BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT] — the gateway exists but emits
 *    fewer outbound flows than the decision has branches.
 * 4. [BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED] — a branch's `nextRef` points at an id
 *    that doesn't exist anywhere in the BPMN.
 * 5. [BpmnFidelityCode.BRANCH_FLOW_MISSING] — a branch's `nextRef` resolves but no sequence
 *    flow path connects this decision's gateway to that target — directly OR via one or more
 *    *semantically transparent* nodes (see [isSemanticallyTransparent]). The transparent-node
 *    walk accepts the legitimate generator pattern where flows pass through unnamed converging
 *    joins; missing-edge bugs are still caught.
 */
@Component
@Suppress("TooManyFunctions") // per-contract-element private helpers (Activity / EndState / Decision)
internal class BpmnContractFidelityChecker {
    private val logger = LoggerFactory.getLogger(BpmnContractFidelityChecker::class.java)

    fun check(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnFidelityReport {
        val issues = mutableListOf<BpmnFidelityIssue>()
        val nodeById = definition.nodes.associateBy { it.id }
        val outgoingBySource = definition.sequences.groupBy { it.sourceRef }

        contract.activities.forEach { activity ->
            checkActivityKind(activity, nodeById, issues)
            checkActivityIteration(activity, nodeById, issues)
            checkActivityLoop(activity, nodeById, issues)
            if (activity is ContractActivity.SubProcess) {
                checkSubProcess(activity, nodeById, definition, issues)
            }
        }

        contract.endStates.forEach { endState ->
            checkEndStateKind(endState, nodeById, issues)
        }

        contract.intermediateThrows.forEach { intermediateThrow ->
            checkIntermediateThrowKind(intermediateThrow, nodeById, issues)
        }

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

    private fun checkDecision(
        decision: ContractDecision,
        nodeById: Map<String, BpmnNode>,
        outgoingBySource: Map<String, List<BpmnEdge>>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val gateway = nodeById[decision.id]
        val validGateway = gateway?.takeIf { it.isGateway() }

        verifyGatewayTypeAndPresence(decision, gateway, issues)

        if (validGateway != null) {
            val outbound = outgoingBySource[validGateway.id].orEmpty()
            verifyOutboundBranchCount(decision, validGateway, outbound, issues)
            verifyDefaultFlow(decision, validGateway, outbound, issues)
        }

        verifyBranchTargetsAndFlows(decision, validGateway, nodeById, outgoingBySource, issues)
    }

    private fun verifyGatewayTypeAndPresence(
        decision: ContractDecision,
        gateway: BpmnNode?,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        when {
            gateway == null -> {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.DECISION_GATEWAY_MISSING,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                        "Decision '${decision.id}' has no corresponding node in the generated BPMN. " +
                            "Under the unified-id convention the gateway must share the decision's id.",
                        contractElementId = decision.id,
                    )
            }

            !gateway.isGateway() -> {
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

            !decision.kind.matchesGatewayType(gateway) -> {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                        "Decision '${decision.id}' declares kind=${decision.kind} but is realized as a " +
                            "${gateway.typeName} node — semantically wrong " +
                            "(${decision.kind} means \"${kindDescription(decision.kind)}\").",
                        contractElementId = decision.id,
                        bpmnElementId = gateway.id,
                    )
            }
        }
    }

    private fun verifyOutboundBranchCount(
        decision: ContractDecision,
        gateway: BpmnNode,
        outbound: List<BpmnEdge>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        if (outbound.size < decision.branches.size) {
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
    }

    private fun verifyDefaultFlow(
        decision: ContractDecision,
        gateway: BpmnNode,
        outbound: List<BpmnEdge>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        // firstOrNull (rather than singleOrNull) keeps this check running even when a
        // contract erroneously declares multiple DefaultBranch entries on the same decision.
        // The multi-default case is caught separately by BpmnContractValidator upstream;
        // here we want to verify the most prominent default against the rendered BPMN
        // rather than silently skipping the entire fidelity check.
        val defaultBranch = decision.branches.filterIsInstance<DefaultBranch>().firstOrNull()
        if (defaultBranch != null) {
            val hasDefaultEdge = outbound.any { it.isDefault }
            if (!hasDefaultEdge) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.DEFAULT_FLOW_MISSING,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                        "Decision '${decision.id}' has a DefaultBranch ('${defaultBranch.id}') " +
                            "but no outbound edge from gateway '${gateway.id}' has isDefault=true. " +
                            "The gateway's BPMN `default` attribute will be absent, leaving the engine " +
                            "with no catch-all flow if no condition matches.",
                        contractElementId = defaultBranch.id,
                        bpmnElementId = gateway.id,
                    )
            }
        }
    }

    private fun verifyBranchTargetsAndFlows(
        decision: ContractDecision,
        gateway: BpmnNode?,
        nodeById: Map<String, BpmnNode>,
        outgoingBySource: Map<String, List<BpmnEdge>>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
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
            if (gateway != null &&
                !targetReachableSemantically(gateway, ref, outgoingBySource, nodeById)
            ) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.BRANCH_FLOW_MISSING,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                        "Branch '${branch.id}' of decision '${decision.id}' specifies nextRef='$ref' " +
                            "but no sequence flow connects gateway '${gateway.id}' to '$ref' " +
                            "(directly or via transparent routing nodes).",
                        contractElementId = branch.id,
                        bpmnElementId = ref,
                    )
            }
        }
    }

    /**
     * Returns true if [targetId] is reachable from [from] either directly or by walking forward
     * through one or more [isSemanticallyTransparent] nodes. Bounded by [MAX_REACHABILITY_HOPS]
     * to keep the check finite on pathological topologies.
     */
    private fun targetReachableSemantically(
        from: BpmnNode,
        targetId: String,
        outgoingBySource: Map<String, List<BpmnEdge>>,
        nodeById: Map<String, BpmnNode>,
    ): Boolean {
        val direct = outgoingBySource[from.id].orEmpty()
        if (direct.any { it.targetRef == targetId }) return true
        val seen = mutableSetOf(from.id)
        var frontier = direct.map { it.targetRef }.toSet() - from.id
        repeat(MAX_REACHABILITY_HOPS) {
            if (frontier.isEmpty()) return false
            if (targetId in frontier) return true
            // Step the BFS one hop: keep only unseen, semantically-transparent nodes; collect
            // their outbound edge targets as the next frontier. Chained pipeline keeps the loop
            // body free of multi-branch jumps (detekt LoopWithTooManyJumpStatements).
            frontier =
                frontier
                    .asSequence()
                    .filter { seen.add(it) }
                    .mapNotNull { nodeById[it] }
                    .filter { it.isSemanticallyTransparent(outgoingBySource) }
                    .flatMap { outgoingBySource[it.id].orEmpty().asSequence() }
                    .map { it.targetRef }
                    .toSet()
        }
        return false
    }

    private companion object {
        // Bound the transparent-join walk. Six hops handles real-world process topologies
        // comfortably and prevents pathological loops from making the check non-terminating.
        const val MAX_REACHABILITY_HOPS = 6
    }

    /**
     * Verifies the BPMN node that realises [activity] has the matching task subtype.
     * Skips silently when no node by the activity id is present in the BPMN — the
     * separate "activity not realised" check (not yet implemented) would catch that.
     */
    private fun checkActivityKind(
        activity: ContractActivity,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val node = nodeById[activity.id] ?: return
        if (activity.matchesTaskType(node)) return
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.ACTIVITY_TASK_KIND_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message =
                "Activity '${activity.id}' declares kind=${activity.kindName} but is realised as a " +
                    "${node.typeName} node — expected ${activity.expectedTaskTypeName()}.",
                contractElementId = activity.id,
                bpmnElementId = node.id,
            )
    }

    /**
     * Verifies the multi-instance marker on a task agrees with its contract activity's
     * `iteration`, in both directions: a declared iteration must be realised with the matching
     * mode, and a single-run activity must NOT carry a multi-instance marker. Non-task
     * realisations are left to [checkActivityKind].
     */
    private fun checkActivityIteration(
        activity: ContractActivity,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val task = nodeById[activity.id] as? BpmnTask ?: return
        val iteration = activity.iteration
        val multiInstance = task.multiInstance
        val message = when {
            iteration != null && multiInstance == null ->
                "Activity '${activity.id}' declares iteration (mode=${iteration.mode}) but its BPMN task " +
                    "carries no multi-instance marker — the per-item semantic was dropped."

            iteration == null && multiInstance != null ->
                "Activity '${activity.id}' does not declare iteration but its BPMN task carries a " +
                    "multi-instance marker (mode=${multiInstance.mode}) — unexpected loop characteristics."

            iteration != null && multiInstance != null && multiInstance.mode != iteration.mode ->
                "Activity '${activity.id}' declares iteration mode=${iteration.mode} but its BPMN task " +
                    "is multi-instance mode=${multiInstance.mode} (isSequential mismatch)."

            else -> return
        }
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.ACTIVITY_ITERATION_MODE_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message = message,
                contractElementId = activity.id,
                bpmnElementId = activity.id,
            )
    }

    /**
     * Verifies that a contract `loop` (standard while/until/retry) is realised as a `standardLoop`
     * marker on the BPMN task, and that the task does not carry an undeclared loop marker.
     */
    private fun checkActivityLoop(
        activity: ContractActivity,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val task = nodeById[activity.id] as? BpmnTask ?: return
        val loop = activity.loop
        val standardLoop = task.standardLoop
        if (loop == null && standardLoop == null) return
        // Past the guard, a null on either side means the other is non-null (presence asymmetry);
        // once both are confirmed non-null the remaining arms compare the loop's properties.
        val message = when {
            standardLoop == null ->
                "Activity '${activity.id}' declares a standard loop but its BPMN task carries no " +
                    "standard-loop marker — the loop semantic was dropped."

            loop == null ->
                "Activity '${activity.id}' does not declare a loop but its BPMN task carries a " +
                    "standard-loop marker — unexpected loop characteristics."

            loop.testBefore != standardLoop.testBefore ->
                "Activity '${activity.id}' declares standard loop testBefore=${loop.testBefore} but its BPMN " +
                    "task is standard loop testBefore=${standardLoop.testBefore} (while/until flipped)."

            loop.loopCondition != standardLoop.loopCondition ->
                "Activity '${activity.id}' declares standard loop condition '${loop.loopCondition}' but its " +
                    "BPMN task has condition '${standardLoop.loopCondition}'."

            loop.loopMaximum != standardLoop.loopMaximum ->
                "Activity '${activity.id}' declares standard loop maximum=${loop.loopMaximum} but its BPMN " +
                    "task has maximum=${standardLoop.loopMaximum}."

            else -> return
        }
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.ACTIVITY_STANDARD_LOOP_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message = message,
                contractElementId = activity.id,
                bpmnElementId = activity.id,
            )
    }

    /**
     * Verifies that an embedded [ContractActivity.SubProcess] is realised as a [BpmnSubProcess]
     * whose declared members are nested inside it and whose boundary no sequence flow crosses:
     * 1. [BpmnFidelityCode.SUBPROCESS_NODE_MISSING] — the subprocess id resolves to no BPMN node.
     *    (A node of the wrong type is reported by [checkActivityKind] as ACTIVITY_TASK_KIND_MISMATCH.)
     * 2. [BpmnFidelityCode.SUBPROCESS_MEMBER_NOT_NESTED] — a member node's `parentRef` is not the
     *    subprocess id, or a sequence flow between two direct members does not carry the subprocess
     *    id as its own `parentRef`, so the grouping the contract declared was dropped.
     * 3. [BpmnFidelityCode.SUBPROCESS_BOUNDARY_CROSSED] — a sequence flow has one endpoint inside
     *    the subprocess and one outside. "Inside" is transitive (a descendant at any nesting depth),
     *    so an edge wholly within a nested subprocess does not register as crossing the outer one.
     */
    private fun checkSubProcess(
        subProcess: ContractActivity.SubProcess,
        nodeById: Map<String, BpmnNode>,
        definition: BpmnDefinition,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val node = nodeById[subProcess.id]
        if (node == null) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.SUBPROCESS_NODE_MISSING,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "Subprocess '${subProcess.id}' has no corresponding node in the generated BPMN. " +
                        "Under the unified-id convention the subprocess node must share the contract id.",
                    contractElementId = subProcess.id,
                )
            return
        }
        // A non-BpmnSubProcess realisation is reported by checkActivityKind (ACTIVITY_TASK_KIND_MISMATCH);
        // without the container we cannot verify nesting, so stop here.
        if (node !is BpmnSubProcess) return

        subProcess.containedActivityIds.forEach { memberId ->
            val memberNode = nodeById[memberId] ?: return@forEach
            if (memberNode.parentRef != subProcess.id) {
                issues +=
                    BpmnFidelityIssue(
                        code = BpmnFidelityCode.SUBPROCESS_MEMBER_NOT_NESTED,
                        severity = BpmnFidelitySeverity.ERROR,
                        message =
                        "Member activity '$memberId' of subprocess '${subProcess.id}' is realised with " +
                            "parentRef='${memberNode.parentRef}' — expected '${subProcess.id}'. The member " +
                            "was left on the enclosing flow rather than nested inside the subprocess.",
                        contractElementId = subProcess.id,
                        bpmnElementId = memberId,
                    )
            }
        }

        definition.sequences.forEach { edge ->
            checkSubProcessEdge(edge, subProcess, nodeById, issues)
        }
    }

    private fun checkSubProcessEdge(
        edge: BpmnEdge,
        subProcess: ContractActivity.SubProcess,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        // "Inside" is transitive so a flow nested in an inner subprocess isn't read as crossing the
        // outer boundary; the own-parentRef check below is by *direct* membership, since a deeper
        // edge legitimately carries the inner subprocess's id, not this one's.
        val sourceInside = isDescendantOf(edge.sourceRef, subProcess.id, nodeById)
        val targetInside = isDescendantOf(edge.targetRef, subProcess.id, nodeById)
        if (sourceInside != targetInside) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.SUBPROCESS_BOUNDARY_CROSSED,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "Sequence flow '${edge.id}' crosses the boundary of subprocess '${subProcess.id}': " +
                        "'${edge.sourceRef}' → '${edge.targetRef}' has one endpoint inside the subprocess " +
                        "and one outside. Embedded subprocesses join the main flow only through their " +
                        "own boundary.",
                    contractElementId = subProcess.id,
                    bpmnElementId = edge.id,
                )
            return
        }
        val betweenDirectMembers = nodeById[edge.sourceRef]?.parentRef == subProcess.id &&
            nodeById[edge.targetRef]?.parentRef == subProcess.id
        if (betweenDirectMembers && edge.parentRef != subProcess.id) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.SUBPROCESS_MEMBER_NOT_NESTED,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "Sequence flow '${edge.id}' between members of subprocess '${subProcess.id}' is " +
                        "realised with parentRef='${edge.parentRef}' — expected '${subProcess.id}'. The " +
                        "flow was left on the enclosing scope rather than nested inside the subprocess.",
                    contractElementId = subProcess.id,
                    bpmnElementId = edge.id,
                )
        }
    }

    /**
     * Whether [nodeId] is nested inside [ancestorId] at any depth, walking the `parentRef` chain.
     * The visited set guards against a malformed parent cycle making the walk non-terminating.
     */
    private fun isDescendantOf(
        nodeId: String?,
        ancestorId: String,
        nodeById: Map<String, BpmnNode>,
    ): Boolean {
        val visited = mutableSetOf<String>()
        var current = nodeById[nodeId]?.parentRef
        while (current != null && visited.add(current)) {
            if (current == ancestorId) return true
            current = nodeById[current]?.parentRef
        }
        return false
    }

    /**
     * Verifies the BPMN node that realises [endState] is a [BpmnEndEvent] whose
     * `eventDefinition` shape matches the contract's end-state kind. Silent when no
     * matching node is present in the BPMN — that's a separate fidelity concern
     * (end-state-not-realised) which the existing checks don't cover and isn't in
     * scope here.
     */
    private fun checkEndStateKind(
        endState: ContractEndState,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val node = nodeById[endState.id] ?: return
        if (node !is BpmnEndEvent) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.END_EVENT_KIND_MISMATCH,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "End state '${endState.id}' declares kind=${endState.kindName} but is realised as a " +
                        "${node.typeName} node — expected END_EVENT.",
                    contractElementId = endState.id,
                    bpmnElementId = node.id,
                )
            return
        }
        if (endState.matchesEventDefinition(node.eventDefinition)) return
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.END_EVENT_KIND_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message =
                "End state '${endState.id}' declares kind=${endState.kindName} but its end event uses " +
                    "${node.eventDefinition::class.simpleName} — expected ${endState.expectedEventDefinitionName()}.",
                contractElementId = endState.id,
                bpmnElementId = node.id,
            )
    }

    private fun ContractEndState.matchesEventDefinition(eventDefinition: BpmnEventDefinition): Boolean = when (this) {
        is ContractEndState.Normal -> eventDefinition is BpmnNoneEventDefinition
        is ContractEndState.Terminate -> eventDefinition is BpmnTerminateEventDefinition
        is ContractEndState.Error -> eventDefinition is BpmnErrorEventDefinition
        is ContractEndState.Message -> eventDefinition is BpmnMessageEventDefinition
        is ContractEndState.Signal -> eventDefinition is BpmnSignalEventDefinition
        is ContractEndState.Escalation -> eventDefinition is BpmnEscalationEventDefinition
    }

    // Class references rather than hardcoded strings so the diagnostic message stays
    // in sync if any event-definition class is renamed (refactor-safe). simpleName is
    // !!-asserted because these are concrete data classes / objects with stable names.
    private fun ContractEndState.expectedEventDefinitionName(): String = when (this) {
        is ContractEndState.Normal -> BpmnNoneEventDefinition::class.simpleName!!
        is ContractEndState.Terminate -> BpmnTerminateEventDefinition::class.simpleName!!
        is ContractEndState.Error -> BpmnErrorEventDefinition::class.simpleName!!
        is ContractEndState.Message -> BpmnMessageEventDefinition::class.simpleName!!
        is ContractEndState.Signal -> BpmnSignalEventDefinition::class.simpleName!!
        is ContractEndState.Escalation -> BpmnEscalationEventDefinition::class.simpleName!!
    }

    private fun checkIntermediateThrowKind(
        intermediateThrow: ContractIntermediateThrow,
        nodeById: Map<String, BpmnNode>,
        issues: MutableList<BpmnFidelityIssue>,
    ) {
        val node = nodeById[intermediateThrow.id]
        if (node == null) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.INTERMEDIATE_THROW_KIND_MISMATCH,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "Intermediate throw '${intermediateThrow.id}' declares kind=${intermediateThrow.kindName} " +
                        "but has no corresponding node in the generated BPMN.",
                    contractElementId = intermediateThrow.id,
                )
            return
        }
        if (node !is BpmnIntermediateThrowEvent) {
            issues +=
                BpmnFidelityIssue(
                    code = BpmnFidelityCode.INTERMEDIATE_THROW_KIND_MISMATCH,
                    severity = BpmnFidelitySeverity.ERROR,
                    message =
                    "Intermediate throw '${intermediateThrow.id}' declares kind=${intermediateThrow.kindName} " +
                        "but is realised as a ${node.typeName} node — expected INTERMEDIATE_THROW_EVENT.",
                    contractElementId = intermediateThrow.id,
                    bpmnElementId = node.id,
                )
            return
        }
        if (intermediateThrow.matchesEventDefinition(node.eventDefinition)) return
        issues +=
            BpmnFidelityIssue(
                code = BpmnFidelityCode.INTERMEDIATE_THROW_KIND_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message =
                "Intermediate throw '${intermediateThrow.id}' declares kind=${intermediateThrow.kindName} " +
                    "but its intermediate throw event uses ${node.eventDefinition::class.simpleName} — " +
                    "expected ${intermediateThrow.expectedEventDefinitionName()}.",
                contractElementId = intermediateThrow.id,
                bpmnElementId = node.id,
            )
    }

    private fun ContractIntermediateThrow.matchesEventDefinition(eventDefinition: BpmnEventDefinition): Boolean = when (this) {
        is ContractIntermediateThrow.Message -> eventDefinition is BpmnMessageEventDefinition
        is ContractIntermediateThrow.Signal -> eventDefinition is BpmnSignalEventDefinition
        is ContractIntermediateThrow.Escalation -> eventDefinition is BpmnEscalationEventDefinition
    }

    private fun ContractIntermediateThrow.expectedEventDefinitionName(): String = when (this) {
        is ContractIntermediateThrow.Message -> BpmnMessageEventDefinition::class.simpleName!!
        is ContractIntermediateThrow.Signal -> BpmnSignalEventDefinition::class.simpleName!!
        is ContractIntermediateThrow.Escalation -> BpmnEscalationEventDefinition::class.simpleName!!
    }
}

private fun BpmnNode.isGateway(): Boolean = this is BpmnGateway

private fun ContractGatewayKind.matchesGatewayType(node: BpmnNode): Boolean = when (this) {
    ContractGatewayKind.EXCLUSIVE -> node is BpmnExclusiveGateway
    ContractGatewayKind.INCLUSIVE -> node is BpmnInclusiveGateway
    ContractGatewayKind.PARALLEL -> node is BpmnParallelGateway
    ContractGatewayKind.EVENT_BASED -> node is BpmnEventBasedGateway
}

private fun kindDescription(kind: ContractGatewayKind): String = when (kind) {
    ContractGatewayKind.EXCLUSIVE -> "pick one branch"
    ContractGatewayKind.INCLUSIVE -> "take any branch whose condition is true"
    ContractGatewayKind.PARALLEL -> "take all branches concurrently"
    ContractGatewayKind.EVENT_BASED -> "wait for the first of several events"
}

private fun ContractActivity.matchesTaskType(node: BpmnNode): Boolean = when (this) {
    is ContractActivity.Service -> node is BpmnServiceTask
    is ContractActivity.User -> node is BpmnUserTask
    is ContractActivity.Script -> node is BpmnScriptTask
    is ContractActivity.BusinessRule -> node is BpmnBusinessRuleTask
    is ContractActivity.Send -> node is BpmnSendTask
    is ContractActivity.Receive -> node is BpmnReceiveTask
    is ContractActivity.Manual -> node is BpmnManualTask
    is ContractActivity.SubProcess -> node is BpmnSubProcess
}

private fun ContractActivity.expectedTaskTypeName(): String = when (this) {
    is ContractActivity.Service -> "SERVICE_TASK"
    is ContractActivity.User -> "USER_TASK"
    is ContractActivity.Script -> "SCRIPT_TASK"
    is ContractActivity.BusinessRule -> "BUSINESS_RULE_TASK"
    is ContractActivity.Send -> "SEND_TASK"
    is ContractActivity.Receive -> "RECEIVE_TASK"
    is ContractActivity.Manual -> "MANUAL_TASK"
    is ContractActivity.SubProcess -> "SUB_PROCESS"
}
