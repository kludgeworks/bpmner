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

class BpmnContractFidelityCheckerTest {
    private val checker = BpmnContractFidelityChecker()

    @Test
    fun `valid loop with back-edge passes`() {
        val contract = repairLoopContract()
        val definition = repairLoopDefinitionWithBackEdge()

        val report = checker.check(contract, definition)

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `missing back-edge flagged as LOOP_BACK_EDGE_MISSING`() {
        val contract = repairLoopContract()
        // Definition lacks the back-edge from Gateway_dec-validate to Task_strategy-1
        val definition = repairLoopDefinitionFlattened()

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.LOOP_BACK_EDGE_MISSING })
    }

    @Test
    fun `gateway with too few outbound flows flagged as GATEWAY_BRANCH_COUNT_INSUFFICIENT`() {
        val contract = repairLoopContract()
        // Definition has the gateway but only one outbound (collapsed branches)
        val definition = repairLoopDefinitionWithCollapsedBranches()

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT })
    }

    @Test
    fun `nextRef pointing at unknown node flagged as BRANCH_NEXT_REF_UNRESOLVED`() {
        val contract =
            ProcessContract(
                id = "c",
                processName = "Test",
                summary = "test",
                trigger = "start",
                triggerSourceIds = listOf("ev1"),
                activities =
                    listOf(
                        ContractActivity(id = "Task_a", name = "A", sourceIds = listOf("ev1")),
                    ),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "dec",
                            question = "Choose?",
                            branches =
                                listOf(
                                    ContractBranch(
                                        id = "br-1",
                                        label = "Option 1",
                                        nextRef = "Task_nonexistent",
                                    ),
                                    ContractBranch(id = "br-2", label = "Option 2"),
                                ),
                            sourceIds = listOf("ev1"),
                        ),
                    ),
                endStates = listOf(ContractEndState(id = "end", name = "End", sourceIds = listOf("ev1"))),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Test",
                nodes =
                    listOf(
                        BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                        BpmnNode(id = "Task_a", name = "A", type = NodeType.USER_TASK),
                        BpmnNode(id = "EndEvent_1", name = "End", type = NodeType.END_EVENT),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "Task_a"),
                        BpmnEdge(id = "F2", sourceRef = "Task_a", targetRef = "EndEvent_1"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED })
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
                        ContractActivity(id = "Task_a", name = "A", sourceIds = listOf("ev1")),
                        ContractActivity(id = "Task_b", name = "B", sourceIds = listOf("ev1")),
                    ),
                endStates = listOf(ContractEndState(id = "end", name = "End", sourceIds = listOf("ev1"))),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Linear",
                nodes =
                    listOf(
                        BpmnNode(id = "StartEvent_1", name = "Start", type = NodeType.START_EVENT),
                        BpmnNode(id = "EndEvent_1", name = "End", type = NodeType.END_EVENT),
                    ),
                sequences = listOf(BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "EndEvent_1")),
            )

        val report = checker.check(contract, definition)

        assertEquals(0, report.issues.size)
        assertTrue(report.isValid)
    }

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
                    ContractActivity(id = "Task_strategy-1", name = "Strategy 1", sourceIds = sources),
                    ContractActivity(id = "Task_strategy-2", name = "Strategy 2", sourceIds = sources),
                    ContractActivity(id = "Task_strategy-3", name = "Strategy 3", sourceIds = sources),
                ),
            decisions =
                listOf(
                    ContractDecision(
                        id = "dec-validate",
                        question = "Did validation pass?",
                        branches =
                            listOf(
                                ContractBranch(id = "br-pass", label = "Pass", nextRef = "EndEvent_success"),
                                ContractBranch(id = "br-fail", label = "Fail", nextRef = "EndEvent_failed"),
                                ContractBranch(id = "br-retry", label = "Retry", nextRef = "Task_strategy-1"),
                            ),
                        sourceIds = sources,
                    ),
                ),
            endStates =
                listOf(
                    ContractEndState(id = "EndEvent_success", name = "Success", sourceIds = sources),
                    ContractEndState(id = "EndEvent_failed", name = "Failed", sourceIds = sources),
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
                    BpmnNode(id = "Task_strategy-1", name = "Strategy 1", type = NodeType.USER_TASK),
                    BpmnNode(id = "Task_strategy-2", name = "Strategy 2", type = NodeType.USER_TASK),
                    BpmnNode(id = "Task_strategy-3", name = "Strategy 3", type = NodeType.USER_TASK),
                    BpmnNode(id = "Gateway_dec-validate", name = "Did validation pass?", type = NodeType.EXCLUSIVE_GATEWAY),
                    BpmnNode(id = "EndEvent_success", name = "Success", type = NodeType.END_EVENT),
                    BpmnNode(id = "EndEvent_failed", name = "Failed", type = NodeType.END_EVENT),
                ),
            sequences =
                listOf(
                    BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "Task_strategy-1"),
                    BpmnEdge(id = "F2", sourceRef = "Task_strategy-1", targetRef = "Task_strategy-2"),
                    BpmnEdge(id = "F3", sourceRef = "Task_strategy-2", targetRef = "Task_strategy-3"),
                    BpmnEdge(id = "F4", sourceRef = "Task_strategy-3", targetRef = "Gateway_dec-validate"),
                    BpmnEdge(id = "F5", sourceRef = "Gateway_dec-validate", targetRef = "EndEvent_success"),
                    BpmnEdge(id = "F6", sourceRef = "Gateway_dec-validate", targetRef = "EndEvent_failed"),
                    BpmnEdge(id = "F7", sourceRef = "Gateway_dec-validate", targetRef = "Task_strategy-1"),
                ),
        )

    private fun repairLoopDefinitionFlattened(): BpmnDefinition {
        val withBack = repairLoopDefinitionWithBackEdge()
        return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F7" })
    }

    private fun repairLoopDefinitionWithCollapsedBranches(): BpmnDefinition {
        val withBack = repairLoopDefinitionWithBackEdge()
        // Keep only one outbound from the gateway (collapsed to a single fail branch)
        return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F6" || it.id == "F7" })
    }
}
