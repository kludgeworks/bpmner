/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnLane
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fidelity checks for the #187 lane feature: an activity the contract assigns to an actor
 * (`actorId`) should be realised inside a matching BPMN lane. Both findings are WARNING
 * severity (lanes are advisory visual structure), so the report stays valid either way.
 */
class BpmnContractFidelityLaneCheckTest {
    private val checker = BpmnContractFidelityChecker()

    @Test
    fun `actor-tagged activity with no lane raises non-blocking LANE_ASSIGNMENT_MISSING`() {
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-lane",
                processName = "Refund",
                summary = "classify then end",
                trigger = "start",
                triggerSourceIds = sources,
                actors = listOf(ContractActor(id = "support", name = "Customer support")),
                activities =
                listOf(
                    ContractActivity.User(
                        id = "act-classify",
                        name = "Classify request",
                        actorId = "support",
                        sourceIds = sources,
                    ),
                ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
            )
        // BPMN realises the activity but emits no lanes.
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Refund",
                nodes =
                listOf(
                    BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                    BpmnUserTask(id = "act-classify", name = "Classify request"),
                    BpmnEndEvent(id = "end-done", name = "Done"),
                ),
                sequences =
                listOf(
                    BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-classify"),
                    BpmnEdge(id = "F2", sourceRef = "act-classify", targetRef = "end-done"),
                ),
            )

        val report = checker.check(contract, definition)

        // WARNING-only — the missing lane must not gate the pipeline.
        assertTrue(report.isValid, "lane findings are WARNING severity; got: ${report.issues}")
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.LANE_ASSIGNMENT_MISSING &&
                    it.message.contains("Customer support")
            },
            "expected LANE_ASSIGNMENT_MISSING citing the actor; got: ${report.issues}",
        )
    }

    @Test
    fun `actor-tagged activity placed in matching lane raises no lane finding`() {
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-lane-ok",
                processName = "Refund",
                summary = "classify then end",
                trigger = "start",
                triggerSourceIds = sources,
                actors = listOf(ContractActor(id = "support", name = "Customer support")),
                activities =
                listOf(
                    ContractActivity.User(
                        id = "act-classify",
                        name = "Classify request",
                        actorId = "support",
                        sourceIds = sources,
                    ),
                ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Refund",
                nodes =
                listOf(
                    BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                    BpmnUserTask(id = "act-classify", name = "Classify request"),
                    BpmnEndEvent(id = "end-done", name = "Done"),
                ),
                sequences =
                listOf(
                    BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-classify"),
                    BpmnEdge(id = "F2", sourceRef = "act-classify", targetRef = "end-done"),
                ),
                lanes =
                listOf(
                    BpmnLane(
                        id = "Lane_support",
                        name = "Customer support",
                        flowNodeRefs = listOf("StartEvent_1", "act-classify", "end-done"),
                    ),
                ),
            )

        val report = checker.check(contract, definition)

        assertTrue(report.isValid)
        assertTrue(
            report.issues.none {
                it.code == BpmnFidelityCode.LANE_ASSIGNMENT_MISSING ||
                    it.code == BpmnFidelityCode.LANE_NAME_MISMATCH
            },
            "expected no lane findings; got: ${report.issues}",
        )
    }
}
