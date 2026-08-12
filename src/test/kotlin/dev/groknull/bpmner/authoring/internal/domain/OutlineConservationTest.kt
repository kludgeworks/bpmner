/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.ProcessContract
import kotlin.test.Test
import kotlin.test.assertTrue

class OutlineConservationTest {
    private val sources = listOf("ev1")

    @Test
    fun `a retry driven only by a process-level diagnostic rejects any drop at all`() {
        // ROLES_DECLARED_BUT_NO_LANES is process-level and carries neither contractElementId nor
        // bpmnElementId, so named = ∅ and conservation is at its strictest.
        val contract = contract()
        val previous = definition(multiInstance = MultiInstanceLoopCharacteristics(MultiInstanceMode.PARALLEL, "each item"))
        val next = definition(multiInstance = null)

        val drops = OutlineConservation.detectDrops(named = emptySet(), contract = contract, previous = previous, next = next)

        assertTrue(drops.any { it.contains("act-review") })
    }

    @Test
    fun `dropping a named task's multi-instance marker is accepted`() {
        val contract = contract()
        val previous = definition(multiInstance = MultiInstanceLoopCharacteristics(MultiInstanceMode.PARALLEL, "each item"))
        val next = definition(multiInstance = null)

        val drops = OutlineConservation.detectDrops(
            named = setOf("act-review"),
            contract = contract,
            previous = previous,
            next = next,
        )

        assertTrue(drops.isEmpty())
    }

    @Test
    fun `a synthesised routing node or an edge is never reported as a drop`() {
        val contract = contract()
        val previous = definition(multiInstance = null)
        val withExtraRoutingNode = previous.copy(
            nodes = previous.nodes + BpmnExclusiveGateway("Gateway_join_1", null),
            sequences = previous.sequences + BpmnEdge("Flow_extra", "act-review", "end-done"),
        )

        // "next" drops the synthesised node/edge "previous" had — not a contract-realised id, so
        // it must never be reported.
        val drops = OutlineConservation.detectDrops(
            named = emptySet(),
            contract = contract,
            previous = withExtraRoutingNode,
            next = previous,
        )

        assertTrue(drops.isEmpty(), "got: $drops")
    }

    private fun contract(): ProcessContract = ProcessContract(
        id = "c-outline",
        processName = "Review",
        summary = "Review each item.",
        start = ContractStart(ContractTrigger.None("Items submitted"), sources),
        activities = listOf(
            ContractActivity(id = "act-review", name = "Review item", sourceIds = sources),
        ),
        actors = listOf(ContractActor(id = "actor-reviewer", name = "Reviewer")),
        endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
    )

    private fun definition(multiInstance: MultiInstanceLoopCharacteristics?): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "Review",
        nodes = listOf(
            BpmnStartEvent("StartEvent_1", "Items submitted"),
            BpmnUserTask("act-review", "Review item", multiInstance = multiInstance),
            BpmnEndEvent("end-done", "Done"),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "act-review"),
            BpmnEdge("Flow_2", "act-review", "end-done"),
        ),
    )
}
