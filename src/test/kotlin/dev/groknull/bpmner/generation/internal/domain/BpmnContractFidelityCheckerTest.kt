/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.NodeType
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
                            node.copy(type = NodeType.USER_TASK)
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
                        BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                        BpmnNode(id = "act-pre-check", name = "Pre-check", type = NodeType.USER_TASK),
                        BpmnNode(id = "dec-pre", name = "Skip detailed path?", type = NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode(id = "act-skip-target", name = "Process", type = NodeType.USER_TASK),
                        BpmnNode(id = "act-detailed-path", name = "Detailed path", type = NodeType.USER_TASK),
                        BpmnNode(id = "end-done", name = "Done", type = NodeType.END_EVENT),
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
                        BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                        BpmnNode(id = "act-a", name = "A", type = NodeType.USER_TASK),
                        BpmnNode(id = "dec-1", name = "Q1?", type = NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode(id = "dec-2", name = "Q2?", type = NodeType.EXCLUSIVE_GATEWAY),
                        BpmnNode(id = "end-1", name = "End 1", type = NodeType.END_EVENT),
                        BpmnNode(id = "end-2", name = "End 2", type = NodeType.END_EVENT),
                        BpmnNode(id = "end-3", name = "End 3", type = NodeType.END_EVENT),
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
                        BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                        BpmnNode(id = "end-done", name = "End", type = NodeType.END_EVENT),
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
                BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                BpmnNode(id = "act-strategy-1", name = "Strategy 1", type = NodeType.USER_TASK),
                BpmnNode(id = "act-strategy-2", name = "Strategy 2", type = NodeType.USER_TASK),
                BpmnNode(id = "act-strategy-3", name = "Strategy 3", type = NodeType.USER_TASK),
                BpmnNode(id = "dec-validate", name = "Did validation pass?", type = NodeType.EXCLUSIVE_GATEWAY),
                BpmnNode(id = "end-success", name = "Success", type = NodeType.END_EVENT),
                BpmnNode(id = "end-failed", name = "Failed", type = NodeType.END_EVENT),
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
                BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                BpmnNode(id = "act-a", name = "A", type = NodeType.USER_TASK),
                BpmnNode(id = "end-done", name = "Done", type = NodeType.END_EVENT),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-a"),
                BpmnEdge(id = "F2", sourceRef = "act-a", targetRef = "end-done"),
            ),
    )
