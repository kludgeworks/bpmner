/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultFlowAssignerTest {
    private val assigner = DefaultFlowAssigner()

    @Test
    fun `sets isDefault on the edge matching DefaultBranch nextRef`() {
        val result = assigner.assign(creditTierContract(), creditTierDefinition())

        val defaultEdge = result.sequences.first { it.id == "Flow_manual" }
        assertTrue(defaultEdge.isDefault, "matched edge must carry isDefault=true")
        assertNull(defaultEdge.conditionExpression, "default edges must not carry a condition")

        val conditionalEdge = result.sequences.first { it.id == "Flow_fast" }
        assertFalse(conditionalEdge.isDefault, "non-default edges unchanged")
        assertEquals("score >= 750", conditionalEdge.conditionExpression)
    }

    @Test
    fun `clears any placeholder condition on the default edge`() {
        // LLM may have invented a placeholder condition like "otherwise" on the catch-all flow.
        // The assigner must wipe it because default flows MUST NOT carry a condition (BPMN spec).
        val polluted =
            creditTierDefinition().let { def ->
                def.copy(
                    sequences =
                        def.sequences.map {
                            if (it.id == "Flow_manual") it.copy(conditionExpression = "otherwise") else it
                        },
                )
            }

        val result = assigner.assign(creditTierContract(), polluted)

        val defaultEdge = result.sequences.first { it.id == "Flow_manual" }
        assertTrue(defaultEdge.isDefault)
        assertNull(defaultEdge.conditionExpression, "placeholder condition must be cleared")
    }

    @Test
    fun `leaves definition unchanged when no DefaultBranch exists`() {
        val contract = creditTierContract().copy(decisions = emptyList())
        val original = creditTierDefinition()
        val result = assigner.assign(contract, original)
        assertEquals(original, result)
    }

    @Test
    fun `skips when DefaultBranch nextRef matches no outbound edge`() {
        val contract =
            creditTierContract().copy(
                decisions =
                    listOf(
                        ContractDecision(
                            id = "Gateway_1",
                            question = "Which credit tier?",
                            branches =
                                listOf(
                                    ConditionalBranch(
                                        id = "br-fast",
                                        label = "Fast-track",
                                        condition = "score >= 750",
                                        nextRef = "Task_fast",
                                    ),
                                    DefaultBranch(
                                        id = "br-manual",
                                        label = "Manual review",
                                        nextRef = "act-nonexistent",
                                    ),
                                ),
                            sourceIds = listOf("ev1"),
                        ),
                    ),
            )
        val original = creditTierDefinition()
        val result = assigner.assign(contract, original)
        // No edge was changed because the nextRef didn't match any outbound target.
        assertEquals(original, result)
    }

    @Test
    fun `picks the single outbound edge when DefaultBranch has no nextRef`() {
        val contract =
            creditTierContract().copy(
                decisions =
                    listOf(
                        ContractDecision(
                            id = "Gateway_solo",
                            question = "Continue?",
                            branches =
                                listOf(
                                    ConditionalBranch(
                                        id = "br-yes",
                                        label = "Yes",
                                        condition = "yes",
                                    ),
                                    DefaultBranch(id = "br-default", label = "Fallback"),
                                ),
                            sourceIds = listOf("ev1"),
                        ),
                    ),
            )
        val original =
            creditTierDefinition().copy(
                nodes =
                    creditTierDefinition().nodes +
                        BpmnExclusiveGateway("Gateway_solo", "Continue?"),
                sequences =
                    creditTierDefinition().sequences +
                        BpmnEdge("Flow_solo", "Gateway_solo", "Task_fast"),
            )
        val result = assigner.assign(contract, original)
        val edge = result.sequences.first { it.id == "Flow_solo" }
        assertTrue(edge.isDefault, "single outbound edge should be marked default when nextRef is null")
    }

    private fun creditTierContract() =
        ProcessContract(
            id = "c-credit",
            processName = "Credit-tier routing",
            summary = "Route by credit score.",
            trigger = "Score received",
            triggerSourceIds = listOf("ev1"),
            activities =
                listOf(
                    ContractActivity(id = "Task_fast", name = "Fast-track", sourceIds = listOf("ev1")),
                    ContractActivity(id = "Task_manual", name = "Manual review", sourceIds = listOf("ev1")),
                ),
            decisions =
                listOf(
                    ContractDecision(
                        id = "Gateway_1",
                        question = "Which credit tier?",
                        branches =
                            listOf(
                                ConditionalBranch(
                                    id = "br-fast",
                                    label = "Fast-track",
                                    condition = "score >= 750",
                                    nextRef = "Task_fast",
                                ),
                                DefaultBranch(
                                    id = "br-manual",
                                    label = "Manual review",
                                    nextRef = "Task_manual",
                                ),
                            ),
                        sourceIds = listOf("ev1"),
                    ),
                ),
            endStates = listOf(ContractEndState(id = "end-offer", name = "Offer generated", sourceIds = listOf("ev1"))),
        )

    private fun creditTierDefinition() =
        BpmnDefinition(
            processId = "P",
            processName = "Credit-tier routing",
            nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Score received"),
                    BpmnExclusiveGateway("Gateway_1", "Which credit tier?"),
                    BpmnUserTask("Task_fast", "Fast-track underwriting"),
                    BpmnUserTask("Task_manual", "Manual review"),
                    BpmnEndEvent("end-offer", "Offer generated"),
                ),
            sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
                    BpmnEdge("Flow_fast", "Gateway_1", "Task_fast", conditionExpression = "score >= 750"),
                    // The LLM emitted this one without isDefault and without condition — exactly the
                    // failure mode the assigner exists to fix.
                    BpmnEdge("Flow_manual", "Gateway_1", "Task_manual"),
                    BpmnEdge("Flow_3", "Task_fast", "end-offer"),
                    BpmnEdge("Flow_4", "Task_manual", "end-offer"),
                ),
        )
}
