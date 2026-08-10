/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorRef
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContractConformancePassTest {
    private val pass = ContractConformancePass()

    @Test
    fun `sets isDefault on the edge matching DefaultBranch nextRef`() {
        val result = pass.conform(creditTierContract(), creditTierDefinition()).definition

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
        // The pass must wipe it because default flows MUST NOT carry a condition (BPMN spec).
        val polluted =
            creditTierDefinition().let { def ->
                def.copy(
                    sequences =
                    def.sequences.map {
                        if (it.id == "Flow_manual") it.copy(conditionExpression = "otherwise") else it
                    },
                )
            }

        val result = pass.conform(creditTierContract(), polluted).definition

        val defaultEdge = result.sequences.first { it.id == "Flow_manual" }
        assertTrue(defaultEdge.isDefault)
        assertNull(defaultEdge.conditionExpression, "placeholder condition must be cleared")
    }

    @Test
    fun `leaves definition unchanged and reports no corrections when nothing needs stamping`() {
        val contract = creditTierContract().copy(decisions = emptyList())
        val original = creditTierDefinition()
        val conformance = pass.conform(contract, original)
        assertEquals(original, conformance.definition)
        assertTrue(conformance.corrections.isEmpty())
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
        val result = pass.conform(contract, original).definition
        // No edge was changed because the nextRef didn't match any outbound target.
        assertEquals(original, result)
    }

    @Test
    fun `picks the single outbound edge when a single-branch decision's DefaultBranch has no nextRef`() {
        // The null-nextRef fallback is only unambiguous for a single-branch decision. A second
        // branch sharing the same sole edge is covered by the ambiguous-multi-branch test below.
        val contract =
            creditTierContract().copy(
                decisions =
                listOf(
                    ContractDecision(
                        id = "Gateway_solo",
                        question = "Continue?",
                        branches = listOf(DefaultBranch(id = "br-default", label = "Fallback")),
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
        val result = pass.conform(contract, original).definition
        val edge = result.sequences.first { it.id == "Flow_solo" }
        assertTrue(edge.isDefault, "single outbound edge should be marked default when nextRef is null")
    }

    @Test
    fun `a diverging gateway whose branches carry labels produces named edges`() {
        // Stamp 2: edge.name = branch.label. generate_bpmn.jinja never asks the model for this,
        // so the model reliably emits unnamed edges; the pass names them from the contract.
        val contract = creditTierContract()
        val unnamed =
            creditTierDefinition().copy(
                sequences =
                creditTierDefinition().sequences.map {
                    if (it.id == "Flow_fast" || it.id == "Flow_manual") it.copy(name = null) else it
                },
            )
        val conformance = pass.conform(contract, unnamed)

        assertEquals("Fast-track", conformance.definition.sequences.first { it.id == "Flow_fast" }.name)
        assertEquals("Manual review", conformance.definition.sequences.first { it.id == "Flow_manual" }.name)
        assertTrue(conformance.corrections.any { it.elementId == "Flow_fast" && it.field == "name" })
        assertTrue(conformance.corrections.any { it.elementId == "Flow_manual" && it.field == "name" })
    }

    @Test
    fun `a correction is produced when the model disagrees with the contract`() {
        val conformance = pass.conform(creditTierContract(), creditTierDefinition())
        // Flow_manual is missing isDefault, and both branch edges are missing their labels —
        // every one of those is a disagreement with the contract's determined value.
        assertTrue(conformance.corrections.isNotEmpty())
    }

    @Test
    fun `no correction is produced when the model already agrees with the contract`() {
        val alreadyConforming = pass.conform(creditTierContract(), creditTierDefinition()).definition
        val conformance = pass.conform(creditTierContract(), alreadyConforming)
        assertTrue(conformance.corrections.isEmpty(), "a second pass over stamped output must be a no-op")
        assertEquals(alreadyConforming, conformance.definition)
    }

    @Test
    fun `stamp 3 - sets task multiInstance from the contract's iteration modifier`() {
        val contract = creditTierContract().copy(
            activities = creditTierContract().activities.map {
                if (it.id == "Task_fast") {
                    (it as ContractActivity.Service).copy(
                        modifiers = ActivityModifiers(
                            iteration = ContractIteration(
                                mode = MultiInstanceMode.PARALLEL,
                                collectionDescription = "each applicant",
                            ),
                        ),
                    )
                } else {
                    it
                }
            },
        )
        val conformance = pass.conform(contract, creditTierDefinition())

        val task = conformance.definition.nodes.first { it.id == "Task_fast" } as BpmnUserTask
        assertEquals(
            MultiInstanceLoopCharacteristics(mode = MultiInstanceMode.PARALLEL, collectionDescription = "each applicant"),
            task.multiInstance,
        )
        assertTrue(conformance.corrections.any { it.elementId == "Task_fast" && it.field == "multiInstance" })
    }

    @Test
    fun `stamp 4 - sets task standardLoop from the contract's loop modifier`() {
        val contract = creditTierContract().copy(
            activities = creditTierContract().activities.map {
                if (it.id == "Task_manual") {
                    (it as ContractActivity.Service).copy(
                        modifiers = ActivityModifiers(
                            loop = ContractLoop(testBefore = false, loopCondition = "not yet approved", loopMaximum = 3),
                        ),
                    )
                } else {
                    it
                }
            },
        )
        val conformance = pass.conform(contract, creditTierDefinition())

        val task = conformance.definition.nodes.first { it.id == "Task_manual" } as BpmnUserTask
        assertEquals(
            StandardLoopCharacteristics(testBefore = false, loopCondition = "not yet approved", loopMaximum = 3),
            task.standardLoop,
        )
        assertTrue(conformance.corrections.any { it.elementId == "Task_manual" && it.field == "standardLoop" })
    }

    @Test
    fun `stamp 5 - sets end-event eventDefinition from the contract's end-state kind`() {
        val contract = creditTierContract().copy(
            endStates = listOf(ContractEndState.Terminate(id = "end-offer", name = "Offer generated", sourceIds = listOf("ev1"))),
        )
        val conformance = pass.conform(contract, creditTierDefinition())

        val endEvent = conformance.definition.nodes.first { it.id == "end-offer" } as BpmnEndEvent
        assertEquals(BpmnTerminateEventDefinition, endEvent.eventDefinition)
        assertTrue(conformance.corrections.any { it.elementId == "end-offer" && it.field == "eventDefinition" })
    }

    @Test
    fun `stamp 5 - end-event ERROR resolution is best-effort against the definition's error catalogue`() {
        val contract = creditTierContract().copy(
            endStates = listOf(
                ContractEndState.Error(
                    id = "end-offer",
                    name = "Offer generated",
                    errorCode = "DECLINED",
                    sourceIds = listOf("ev1"),
                ),
            ),
        )
        val withCatalogueEntry = creditTierDefinition().copy(errors = listOf(BpmnErrorRef(id = "Error_1", code = "DECLINED")))

        val conformance = pass.conform(contract, withCatalogueEntry)
        val endEvent = conformance.definition.nodes.first { it.id == "end-offer" } as BpmnEndEvent
        assertEquals("Error_1", (endEvent.eventDefinition as dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition).errorRef)

        // Without a matching catalogue entry, the stamp cannot resolve and leaves the node untouched
        // — inventing a catalogue entry is structural synthesis, out of scope for this pass.
        val withoutCatalogueEntry = creditTierDefinition()
        val unresolved = pass.conform(contract, withoutCatalogueEntry)
        val unresolvedEndEvent = unresolved.definition.nodes.first { it.id == "end-offer" } as BpmnEndEvent
        assertEquals(BpmnNoneEventDefinition, unresolvedEndEvent.eventDefinition)
    }

    @Test
    fun `stamp 6 - sets intermediate-throw eventDefinition from a resolvable message catalogue entry`() {
        val throwNode = BpmnIntermediateThrowEvent(id = "throw-1", eventDefinition = BpmnNoneEventDefinition)
        val definition = creditTierDefinition().copy(
            nodes = creditTierDefinition().nodes + throwNode,
            messages = listOf(BpmnMessageRef(id = "Message_1", name = "receipt email")),
        )
        val contract = creditTierContract().copy(
            intermediateThrows = listOf(
                ContractIntermediateThrow.Message(id = "throw-1", name = "Send receipt", messageName = "receipt email"),
            ),
        )

        val conformance = pass.conform(contract, definition)
        val stamped = conformance.definition.nodes.first { it.id == "throw-1" } as BpmnIntermediateThrowEvent
        assertEquals(BpmnMessageEventDefinition("Message_1"), stamped.eventDefinition)
        assertTrue(conformance.corrections.any { it.elementId == "throw-1" && it.field == "eventDefinition" })
    }

    @Test
    fun `stamp 7 - substitutes the gateway node subtype to match the contract's declared kind`() {
        val contract = creditTierContract().copy(
            decisions = creditTierContract().decisions.map { it.copy(kind = ContractGatewayKind.PARALLEL) },
        )
        val conformance = pass.conform(contract, creditTierDefinition())

        val gateway = conformance.definition.nodes.first { it.id == "Gateway_1" }
        assertIs<BpmnParallelGateway>(gateway)
        assertEquals("Which credit tier?", gateway.name)
        assertTrue(conformance.corrections.any { it.elementId == "Gateway_1" && it.field == "gatewayKind" })
    }

    @Test
    fun `stamp 7 - no correction when the gateway node already matches the declared kind`() {
        // creditTierContract's decision defaults to EXCLUSIVE, matching creditTierDefinition's
        // BpmnExclusiveGateway node — no substitution should occur.
        val conformance = pass.conform(creditTierContract(), creditTierDefinition())
        assertFalse(conformance.corrections.any { it.field == "gatewayKind" })
    }

    @Test
    fun `resolveOutboundEdge's null-nextRef fallback does not stamp an ambiguous multi-branch decision`() {
        // A decision with two branches but only one outbound edge is a GATEWAY_BRANCH_COUNT_INSUFFICIENT
        // topology the fidelity checker will reject and retry — the conformance pass must not guess
        // which branch owns the sole edge by stamping both onto it.
        val contract = creditTierContract().copy(
            decisions = listOf(
                ContractDecision(
                    id = "Gateway_solo",
                    question = "Continue?",
                    branches = listOf(
                        ConditionalBranch(id = "br-yes", label = "Yes", condition = "yes", nextRef = "Task_fast"),
                        DefaultBranch(id = "br-default", label = "Fallback"),
                    ),
                    sourceIds = listOf("ev1"),
                ),
            ),
        )
        val original = creditTierDefinition().copy(
            nodes = creditTierDefinition().nodes + BpmnExclusiveGateway("Gateway_solo", "Continue?"),
            sequences = creditTierDefinition().sequences + BpmnEdge("Flow_solo", "Gateway_solo", "Task_fast"),
        )

        val result = pass.conform(contract, original).definition
        val edge = result.sequences.first { it.id == "Flow_solo" }
        assertFalse(edge.isDefault, "ambiguous fallback must not mark the shared edge default")
        assertEquals("Yes", edge.name, "the nextRef-matched branch's label still stamps unambiguously")
    }

    private fun creditTierContract() = ProcessContract(
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

    private fun creditTierDefinition(): BpmnDefinition = BpmnDefinition(
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
            BpmnEdge("Flow_fast", "Gateway_1", "Task_fast", name = "Fast-track", conditionExpression = "score >= 750"),
            // The LLM emitted this one without isDefault, without a condition, and without a
            // name — exactly the failure modes the pass exists to fix.
            BpmnEdge("Flow_manual", "Gateway_1", "Task_manual"),
            BpmnEdge("Flow_3", "Task_fast", "end-offer"),
            BpmnEdge("Flow_4", "Task_manual", "end-offer"),
        ),
    )
}
