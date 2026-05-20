/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnFidelityCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Convention exercised by every fixture below: contract element ids (`act-…`, `dec-…`, `end-…`)
 * are used verbatim as BPMN node ids. Element kind is carried by `BpmnNode.type`. See `BpmnDomain.kt`
 * and `BpmnContractGenerationPromptFactory.kt` for the canonical generator instructions.
 */
class BpmnContractFidelityCheckerTest {
    private val checker = BpmnContractFidelityChecker()

    @Test
    fun `valid loop with back-edge passes`() {
        val report = checker.check(repairLoopContract(), repairLoopDefinitionWithBackEdge())

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `missing branch flow flagged as BRANCH_FLOW_MISSING`() {
        // Definition lacks the back-edge from dec-validate to act-strategy-1
        val report = checker.check(repairLoopContract(), repairLoopDefinitionFlattened())

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.BRANCH_FLOW_MISSING },
            "expected BRANCH_FLOW_MISSING; got: ${report.issues.map { it.code }}",
        )
    }

    @Test
    fun `gateway with too few outbound flows flagged as GATEWAY_BRANCH_COUNT_INSUFFICIENT`() {
        // Definition has the gateway but only one outbound (collapsed branches)
        val report = checker.check(repairLoopContract(), repairLoopDefinitionWithCollapsedBranches())

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT })
    }

    @Test
    fun `nextRef pointing at unknown node flagged as BRANCH_NEXT_REF_UNRESOLVED`() {
        val contract = unresolvedRefContract()
        val definition = unresolvedRefDefinition()

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED })
    }

    @Test
    fun `decision without a corresponding gateway flagged as DECISION_GATEWAY_MISSING`() {
        // Contract declares dec-choose but the BPMN never emits a node with that id.
        val contract = unresolvedRefContract()
        val definitionWithoutDecisionNode = unresolvedRefDefinition()

        val report = checker.check(contract, definitionWithoutDecisionNode)

        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.DECISION_GATEWAY_MISSING },
            "expected DECISION_GATEWAY_MISSING; got: ${report.issues.map { it.code }}",
        )
    }

    @Test
    fun `decision realized as a non-gateway node type flagged as DECISION_GATEWAY_MISSING`() {
        val contract = repairLoopContract()
        val original = repairLoopDefinitionWithBackEdge()
        val withDecisionAsTask =
            original.copy(
                nodes =
                    original.nodes.map { node ->
                        if (node.id == "dec-validate") {
                            BpmnUserTask(id = node.id, name = node.name)
                        } else {
                            node
                        }
                    },
            )

        val report = checker.check(contract, withDecisionAsTask)

        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.DECISION_GATEWAY_MISSING && it.message.contains("USER_TASK")
            },
            "expected DECISION_GATEWAY_MISSING citing USER_TASK; got: ${report.issues}",
        )
    }

    @Test
    fun `forward-skip branch with a real edge does NOT trigger a false back-edge flag`() {
        // Regression test for the pre-rewrite fragile heuristic: a forward-skip branch whose
        // target is realised by a real sequence flow must NOT be flagged.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-skip",
                processName = "Forward skip",
                summary = "decision skips an intermediate activity",
                trigger = "start",
                triggerSourceIds = sources,
                activities =
                    listOf(
                        ContractActivity(id = "act-pre-check", name = "Pre-check", sourceIds = sources),
                        ContractActivity(id = "act-skip-target", name = "Process", sourceIds = sources),
                        ContractActivity(id = "act-detailed-path", name = "Detailed path", sourceIds = sources),
                    ),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "dec-pre",
                            question = "Skip detailed path?",
                            branches =
                                listOf(
                                    ContractBranch(id = "br-skip", label = "Yes", nextRef = "act-skip-target"),
                                    ContractBranch(id = "br-detailed", label = "No", nextRef = "act-detailed-path"),
                                ),
                            sourceIds = sources,
                        ),
                    ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Forward skip",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-pre-check", name = "Pre-check"),
                        BpmnExclusiveGateway(id = "dec-pre", name = "Skip detailed path?"),
                        BpmnUserTask(id = "act-skip-target", name = "Process"),
                        BpmnUserTask(id = "act-detailed-path", name = "Detailed path"),
                        BpmnEndEvent(id = "end-done", name = "Done"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-pre-check"),
                        BpmnEdge(id = "F2", sourceRef = "act-pre-check", targetRef = "dec-pre"),
                        BpmnEdge(id = "F3", sourceRef = "dec-pre", targetRef = "act-skip-target"),
                        BpmnEdge(id = "F4", sourceRef = "dec-pre", targetRef = "act-detailed-path"),
                        BpmnEdge(id = "F5", sourceRef = "act-detailed-path", targetRef = "act-skip-target"),
                        BpmnEdge(id = "F6", sourceRef = "act-skip-target", targetRef = "end-done"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertTrue(report.isValid, "forward-skip must not flag any fidelity issue; got: ${report.issues}")
    }

    @Suppress("LongMethod") // explicit fixture; splitting hides the multi-decision shape
    @Test
    fun `per-decision gateway lookup catches insufficient outbound for THIS decision even when another gateway has enough`() {
        // Regression test for the pre-rewrite global-max bug: D1 has 2 branches mapped to a
        // 1-outbound gateway while D2 has 3 branches mapped to a 3-outbound gateway. The
        // checker must flag D1 even though some OTHER gateway in the BPMN has ≥2 outbound.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-multi",
                processName = "Two decisions",
                summary = "multi-decision contract",
                trigger = "start",
                triggerSourceIds = sources,
                activities = listOf(ContractActivity(id = "act-a", name = "A", sourceIds = sources)),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "dec-1",
                            question = "Q1?",
                            branches =
                                listOf(
                                    ContractBranch(id = "br-1a", label = "1a"),
                                    ContractBranch(id = "br-1b", label = "1b"),
                                ),
                            sourceIds = sources,
                        ),
                        ContractDecision(
                            id = "dec-2",
                            question = "Q2?",
                            branches =
                                listOf(
                                    ContractBranch(id = "br-2a", label = "2a"),
                                    ContractBranch(id = "br-2b", label = "2b"),
                                    ContractBranch(id = "br-2c", label = "2c"),
                                ),
                            sourceIds = sources,
                        ),
                    ),
                endStates =
                    listOf(
                        ContractEndState(id = "end-1", name = "End 1", sourceIds = sources),
                        ContractEndState(id = "end-2", name = "End 2", sourceIds = sources),
                        ContractEndState(id = "end-3", name = "End 3", sourceIds = sources),
                    ),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Two decisions",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-a", name = "A"),
                        BpmnExclusiveGateway(id = "dec-1", name = "Q1?"),
                        BpmnExclusiveGateway(id = "dec-2", name = "Q2?"),
                        BpmnEndEvent(id = "end-1", name = "End 1"),
                        BpmnEndEvent(id = "end-2", name = "End 2"),
                        BpmnEndEvent(id = "end-3", name = "End 3"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-a"),
                        BpmnEdge(id = "F2", sourceRef = "act-a", targetRef = "dec-1"),
                        // dec-1 has only ONE outbound (the bug case)
                        BpmnEdge(id = "F3", sourceRef = "dec-1", targetRef = "dec-2"),
                        // dec-2 has THREE outbound (the global-max distractor)
                        BpmnEdge(id = "F4", sourceRef = "dec-2", targetRef = "end-1"),
                        BpmnEdge(id = "F5", sourceRef = "dec-2", targetRef = "end-2"),
                        BpmnEdge(id = "F6", sourceRef = "dec-2", targetRef = "end-3"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        val countIssues =
            report.issues.filter { it.code == BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT }
        assertTrue(
            countIssues.any { it.contractElementId == "dec-1" },
            "expected GATEWAY_BRANCH_COUNT_INSUFFICIENT for dec-1; got: $countIssues",
        )
        assertTrue(
            countIssues.none { it.contractElementId == "dec-2" },
            "dec-2 has 3 outbound for 3 branches and must not be flagged; got: $countIssues",
        )
    }

    @Test
    fun `PARALLEL decision realized as BpmnParallelGateway passes`() {
        val report = checker.check(parallelForkContract(), parallelForkDefinition(useParallelFork = true))

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `PARALLEL decision realized as BpmnExclusiveGateway flagged as DECISION_GATEWAY_KIND_MISMATCH`() {
        // Generator emitted EXCLUSIVE for the fork — the bug employee-onboarding hit. The
        // alignment LLM caught it semantically; the fidelity checker must catch it structurally.
        val report = checker.check(parallelForkContract(), parallelForkDefinition(useParallelFork = false))

        assertFalse(report.isValid)
        val mismatches = report.issues.filter { it.code == BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH }
        assertTrue(
            mismatches.any {
                it.contractElementId == "dec-prep-tracks" &&
                    it.message.contains("PARALLEL") &&
                    it.message.contains("EXCLUSIVE_GATEWAY")
            },
            "expected DECISION_GATEWAY_KIND_MISMATCH for dec-prep-tracks citing PARALLEL vs EXCLUSIVE_GATEWAY; got: $mismatches",
        )
    }

    @Test
    fun `EXCLUSIVE decision realized as BpmnParallelGateway also flagged as DECISION_GATEWAY_KIND_MISMATCH`() {
        // The symmetric error: contract was a choice but the BPMN expressed it as a parallel split.
        val contract = repairLoopContract()
        val original = repairLoopDefinitionWithBackEdge()
        val withParallelGateway =
            original.copy(
                nodes =
                    original.nodes.map { node ->
                        if (node.id == "dec-validate") {
                            BpmnParallelGateway(id = node.id, name = node.name)
                        } else {
                            node
                        }
                    },
            )

        val report = checker.check(contract, withParallelGateway)

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH &&
                    it.message.contains("EXCLUSIVE") &&
                    it.message.contains("PARALLEL_GATEWAY")
            },
            "expected DECISION_GATEWAY_KIND_MISMATCH citing EXCLUSIVE vs PARALLEL_GATEWAY; got: ${report.issues}",
        )
    }

    @Test
    fun `contract without decisions returns empty report`() {
        val contract =
            ProcessContract(
                id = "c",
                processName = "Linear",
                summary = "linear",
                trigger = "start",
                triggerSourceIds = listOf("ev1"),
                activities =
                    listOf(
                        ContractActivity(id = "act-a", name = "A", sourceIds = listOf("ev1")),
                        ContractActivity(id = "act-b", name = "B", sourceIds = listOf("ev1")),
                    ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = listOf("ev1"))),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Linear",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnEndEvent(id = "end-done", name = "End"),
                    ),
                sequences = listOf(BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "end-done")),
            )

        val report = checker.check(contract, definition)

        assertEquals(0, report.issues.size)
        assertTrue(report.isValid)
    }
}

// ----- Fixtures -----

private fun repairLoopContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-repair",
        processName = "Repair loop",
        summary = "Iterative repair with three exit conditions.",
        trigger = "request",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity(id = "act-strategy-1", name = "Strategy 1", sourceIds = sources),
                ContractActivity(id = "act-strategy-2", name = "Strategy 2", sourceIds = sources),
                ContractActivity(id = "act-strategy-3", name = "Strategy 3", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-validate",
                    question = "Did validation pass?",
                    branches =
                        listOf(
                            ContractBranch(id = "br-pass", label = "Pass", nextRef = "end-success"),
                            ContractBranch(id = "br-fail", label = "Fail", nextRef = "end-failed"),
                            ContractBranch(id = "br-retry", label = "Retry", nextRef = "act-strategy-1"),
                        ),
                    sourceIds = sources,
                ),
            ),
        endStates =
            listOf(
                ContractEndState(id = "end-success", name = "Success", sourceIds = sources),
                ContractEndState(id = "end-failed", name = "Failed", sourceIds = sources),
            ),
    )
}

private fun repairLoopDefinitionWithBackEdge(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Repair loop",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-strategy-1", name = "Strategy 1"),
                BpmnUserTask(id = "act-strategy-2", name = "Strategy 2"),
                BpmnUserTask(id = "act-strategy-3", name = "Strategy 3"),
                BpmnExclusiveGateway(id = "dec-validate", name = "Did validation pass?"),
                BpmnEndEvent(id = "end-success", name = "Success"),
                BpmnEndEvent(id = "end-failed", name = "Failed"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-strategy-1"),
                BpmnEdge(id = "F2", sourceRef = "act-strategy-1", targetRef = "act-strategy-2"),
                BpmnEdge(id = "F3", sourceRef = "act-strategy-2", targetRef = "act-strategy-3"),
                BpmnEdge(id = "F4", sourceRef = "act-strategy-3", targetRef = "dec-validate"),
                BpmnEdge(id = "F5", sourceRef = "dec-validate", targetRef = "end-success"),
                BpmnEdge(id = "F6", sourceRef = "dec-validate", targetRef = "end-failed"),
                BpmnEdge(id = "F7", sourceRef = "dec-validate", targetRef = "act-strategy-1"),
            ),
    )

private fun repairLoopDefinitionFlattened(): BpmnDefinition {
    val withBack = repairLoopDefinitionWithBackEdge()
    return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F7" })
}

private fun repairLoopDefinitionWithCollapsedBranches(): BpmnDefinition {
    val withBack = repairLoopDefinitionWithBackEdge()
    return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F6" || it.id == "F7" })
}

private fun parallelForkContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-parallel",
        processName = "Three concurrent tracks",
        summary = "Fork into three independent preparation tracks then rejoin.",
        trigger = "Hire confirmed",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity(id = "act-prep-it", name = "IT prep", sourceIds = sources),
                ContractActivity(id = "act-prep-facilities", name = "Facilities prep", sourceIds = sources),
                ContractActivity(id = "act-prep-manager", name = "Manager prep", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-prep-tracks",
                    question = "Run all preparation tracks",
                    branches =
                        listOf(
                            ContractBranch(id = "br-it", label = "IT", nextRef = "act-prep-it"),
                            ContractBranch(id = "br-fac", label = "Facilities", nextRef = "act-prep-facilities"),
                            ContractBranch(id = "br-mgr", label = "Manager", nextRef = "act-prep-manager"),
                        ),
                    kind = ContractGatewayKind.PARALLEL,
                    sourceIds = sources,
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-onboarded", name = "Onboarded", sourceIds = sources)),
    )
}

private fun parallelForkDefinition(useParallelFork: Boolean): BpmnDefinition {
    val fork =
        if (useParallelFork) {
            BpmnParallelGateway(id = "dec-prep-tracks", name = "Run all preparation tracks")
        } else {
            BpmnExclusiveGateway(id = "dec-prep-tracks", name = "Run all preparation tracks")
        }
    return BpmnDefinition(
        processId = "P",
        processName = "Three concurrent tracks",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Hire confirmed"),
                fork,
                BpmnUserTask(id = "act-prep-it", name = "IT prep"),
                BpmnUserTask(id = "act-prep-facilities", name = "Facilities prep"),
                BpmnUserTask(id = "act-prep-manager", name = "Manager prep"),
                BpmnParallelGateway(id = "Gateway_join_prep", name = null),
                BpmnEndEvent(id = "end-onboarded", name = "Onboarded"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "dec-prep-tracks"),
                BpmnEdge(id = "F2", sourceRef = "dec-prep-tracks", targetRef = "act-prep-it"),
                BpmnEdge(id = "F3", sourceRef = "dec-prep-tracks", targetRef = "act-prep-facilities"),
                BpmnEdge(id = "F4", sourceRef = "dec-prep-tracks", targetRef = "act-prep-manager"),
                BpmnEdge(id = "F5", sourceRef = "act-prep-it", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F6", sourceRef = "act-prep-facilities", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F7", sourceRef = "act-prep-manager", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F8", sourceRef = "Gateway_join_prep", targetRef = "end-onboarded"),
            ),
    )
}

private fun unresolvedRefContract() =
    ProcessContract(
        id = "c",
        processName = "Test",
        summary = "test",
        trigger = "start",
        triggerSourceIds = listOf("ev1"),
        activities = listOf(ContractActivity(id = "act-a", name = "A", sourceIds = listOf("ev1"))),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-choose",
                    question = "Choose?",
                    branches =
                        listOf(
                            ContractBranch(id = "br-1", label = "Option 1", nextRef = "act-nonexistent"),
                            ContractBranch(id = "br-2", label = "Option 2"),
                        ),
                    sourceIds = listOf("ev1"),
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = listOf("ev1"))),
    )

private fun unresolvedRefDefinition() =
    BpmnDefinition(
        processId = "P",
        processName = "Test",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-a", name = "A"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-a"),
                BpmnEdge(id = "F2", sourceRef = "act-a", targetRef = "end-done"),
            ),
    )
