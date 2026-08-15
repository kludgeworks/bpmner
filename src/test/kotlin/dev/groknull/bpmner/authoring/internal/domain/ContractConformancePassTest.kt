/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnEscalationEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEscalationRef
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnSignalEventDefinition
import dev.groknull.bpmner.bpmn.BpmnSignalRef
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractFlow
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIteration
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `skips when a branch's flow target matches no outbound edge`() {
        val contract =
            creditTierContract().copy(
                flows = listOf(
                    ContractFlow.Sequence(from = "start", to = "Gateway_1"),
                    ContractFlow.Branch(from = "Gateway_1", to = "Task_fast", branchId = "br-fast"),
                    ContractFlow.Branch(from = "Gateway_1", to = "act-nonexistent", branchId = "br-manual"),
                ),
            )
        val original = creditTierDefinition()
        val result = pass.conform(contract, original).definition
        // No edge was changed because the flow target didn't match any outbound target.
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
    fun `stamps branch edge redirected through a unique converging join`() {
        val redirected = creditTierDefinition().copy(
            nodes = creditTierDefinition().nodes + BpmnExclusiveGateway("Join_manual", null),
            sequences = creditTierDefinition().sequences.map {
                if (it.id == "Flow_manual") it.copy(targetRef = "Join_manual") else it
            } + listOf(
                BpmnEdge("Flow_aux_manual", "StartEvent_1", "Join_manual"),
                BpmnEdge("Flow_join_manual", "Join_manual", "Task_manual"),
            ),
        )

        val conformance = pass.conform(creditTierContract(), redirected)
        val branchEdge = conformance.definition.sequences.single { it.id == "Flow_manual" }

        assertTrue(branchEdge.isDefault)
        assertEquals("Manual review", branchEdge.name)
        assertEquals("Task_manual", conformance.definition.sequences.single { it.id == "Flow_join_manual" }.targetRef)
        assertEquals(conformance.definition, pass.conform(creditTierContract(), conformance.definition).definition)
    }

    @Test
    fun `does not infer an ambiguous or non-converging redirected branch`() {
        val ambiguous = creditTierDefinition().copy(
            nodes = creditTierDefinition().nodes +
                BpmnExclusiveGateway("Join_1", null) +
                BpmnExclusiveGateway("Join_2", null),
            sequences = creditTierDefinition().sequences.map {
                if (it.id == "Flow_manual" || it.id == "Flow_fast") {
                    it.copy(targetRef = if (it.id == "Flow_manual") "Join_1" else "Join_2")
                } else {
                    it
                }
            } + listOf(
                BpmnEdge("Flow_join_1", "Join_1", "Task_manual"),
                BpmnEdge("Flow_join_2", "Join_2", "Task_manual"),
            ),
        )
        val nonConverging = ambiguous.copy(
            sequences = ambiguous.sequences.filterNot { it.id == "Flow_1" },
        )

        val ambiguousEdge = pass.conform(creditTierContract(), ambiguous).definition.sequences.single { it.id == "Flow_manual" }
        val nonConvergingEdge = pass.conform(creditTierContract(), nonConverging).definition.sequences.single {
            it.id == "Flow_manual"
        }
        assertFalse(ambiguousEdge.isDefault)
        assertFalse(nonConvergingEdge.isDefault)
    }

    @Test
    fun `does not use a redirected edge when direct branch matches are ambiguous`() {
        val ambiguous = creditTierDefinition().copy(
            nodes = creditTierDefinition().nodes + BpmnExclusiveGateway("Join_manual", null),
            sequences = creditTierDefinition().sequences.map {
                if (it.id == "Flow_fast") it.copy(targetRef = "Join_manual") else it
            } + listOf(
                BpmnEdge("Flow_duplicate_manual", "Gateway_1", "Task_manual"),
                BpmnEdge("Flow_aux_manual", "StartEvent_1", "Join_manual"),
                BpmnEdge("Flow_join_manual", "Join_manual", "Task_manual"),
            ),
        )

        val result = pass.conform(creditTierContract(), ambiguous).definition

        assertFalse(result.sequences.single { it.id == "Flow_fast" }.isDefault)
        assertEquals("Fast-track", result.sequences.single { it.id == "Flow_fast" }.name)
    }

    @Test
    fun `stamps a contract determined standard loop annotation and association`() {
        val activity = ContractActivity.Service(
            id = "Task_retry",
            name = "Retry charge",
            modifiers = ActivityModifiers(loop = ContractLoop(testBefore = false, loopCondition = "charge succeeds")),
        )
        val conformance = pass.conform(
            creditTierContract().copy(activities = listOf(activity)),
            definitionWithTask(activity.id),
        )

        assertEquals("Loop until charge succeeds", conformance.definition.annotations.single().text)
        assertEquals("TextAnnotation_Task_retry_standardLoop", conformance.definition.annotations.single().id)
        assertEquals("Task_retry", conformance.definition.associations.single().sourceRef)
        assertEquals("TextAnnotation_Task_retry_standardLoop", conformance.definition.associations.single().targetRef)
        assertIdempotent(activity, conformance.definition)
    }

    @Test
    fun `stamps a contract determined multi instance annotation and association`() {
        val activity = ContractActivity.Service(
            id = "Task_review",
            name = "Review applications",
            modifiers = ActivityModifiers(
                iteration = ContractIteration(MultiInstanceMode.PARALLEL, "applications"),
            ),
        )
        val conformance = pass.conform(
            creditTierContract().copy(activities = listOf(activity)),
            definitionWithTask(activity.id),
        )

        assertEquals("For each applications", conformance.definition.annotations.single().text)
        assertEquals("TextAnnotation_Task_review_multiInstance", conformance.definition.annotations.single().id)
        assertEquals("Task_review", conformance.definition.associations.single().sourceRef)
        assertEquals("TextAnnotation_Task_review_multiInstance", conformance.definition.associations.single().targetRef)
        assertIdempotent(activity, conformance.definition)
    }

    private fun assertIdempotent(activity: ContractActivity, definition: BpmnDefinition) {
        val result = pass.conform(creditTierContract().copy(activities = listOf(activity)), definition)
        assertEquals(definition, result.definition)
        assertTrue(result.corrections.isEmpty())
    }

    private fun definitionWithTask(taskId: String) = BpmnDefinition(
        processId = "P",
        processName = "Loop process",
        nodes = listOf(BpmnUserTask(taskId, "Task")),
        sequences = emptyList(),
    )

    // A correct signal end event must survive the pass. Normal resolves unconditionally, so a
    // kind the resolver does not handle is silently stamped back to BpmnNoneEventDefinition and
    // the semantics vanish with the run still green.
    @Test
    fun `leaves a correctly-signalled end event untouched`() {
        val result = pass.conform(signalContract(), signalDefinition())

        val end = result.definition.nodes.filterIsInstance<BpmnEndEvent>().single()
        assertEquals(
            BpmnSignalEventDefinition("Signal_1"),
            end.eventDefinition,
            "a contract-declared signal end event must survive conformance unchanged",
        )
        assertTrue(
            result.corrections.none { it.elementId == "end-broadcast" },
            "no correction may be reported for an end event that already matches the contract",
        )
    }

    @Test
    fun `stamps a signal end event the model left as a plain end`() {
        val definition = signalDefinition(endEventDefinition = BpmnNoneEventDefinition)

        val result = pass.conform(signalContract(), definition)

        val end = result.definition.nodes.filterIsInstance<BpmnEndEvent>().single()
        assertEquals(BpmnSignalEventDefinition("Signal_1"), end.eventDefinition)
        assertTrue(result.corrections.any { it.elementId == "end-broadcast" })
    }

    // Mirrors the ERROR/MESSAGE rule: resolution needs a catalogue entry and this
    // pass never invents one, so an absent catalogue means no stamp rather than a fabricated ref.
    @Test
    fun `does not stamp a signal end event when the signal catalogue is empty`() {
        val definition = signalDefinition(endEventDefinition = BpmnNoneEventDefinition, signals = emptyList())

        val result = pass.conform(signalContract(), definition)

        val end = result.definition.nodes.filterIsInstance<BpmnEndEvent>().single()
        assertEquals(BpmnNoneEventDefinition, end.eventDefinition)
        assertTrue(result.corrections.none { it.elementId == "end-broadcast" })
    }

    @Test
    fun `stamps an escalation intermediate throw from the escalation catalogue`() {
        val contract = ProcessContract(
            id = "c-esc",
            processName = "Approval chase",
            summary = "Escalates an overdue approval",
            start = ContractStart(ContractTrigger.None("approval overdue"), listOf("S1")),
            activities = listOf(
                ContractActivity.Service(id = "act-chase", name = "Chase approver", sourceIds = listOf("S1")),
            ),
            intermediateThrows = listOf(
                ContractIntermediateThrow.Escalation(
                    id = "throw-esc",
                    name = "Raise escalation",
                    escalationCode = "APPROVAL_OVERDUE",
                    sourceIds = listOf("S1"),
                ),
            ),
            endStates = listOf(ContractEndState.Normal("end-done", "Done", sourceIds = listOf("S1"))),
        )
        val definition = BpmnDefinition(
            processId = "P",
            processName = "Approval chase",
            nodes = listOf(
                BpmnStartEvent("StartEvent_1", "Approval overdue"),
                BpmnIntermediateThrowEvent("throw-esc", "Raise escalation", BpmnNoneEventDefinition),
                BpmnUserTask("act-chase", "Chase approver"),
                BpmnEndEvent("end-done", "Done"),
            ),
            sequences = listOf(
                BpmnEdge("Flow_1", "StartEvent_1", "throw-esc"),
                BpmnEdge("Flow_2", "throw-esc", "act-chase"),
                BpmnEdge("Flow_3", "act-chase", "end-done"),
            ),
            escalations = listOf(BpmnEscalationRef("Escalation_1", "APPROVAL_OVERDUE")),
        )

        val result = pass.conform(contract, definition)

        val thrown = result.definition.nodes.filterIsInstance<BpmnIntermediateThrowEvent>().single()
        assertEquals(BpmnEscalationEventDefinition("Escalation_1"), thrown.eventDefinition)
    }

    private fun signalContract() = ProcessContract(
        id = "c-signal",
        processName = "Settlement broadcast",
        summary = "Broadcasts settlement completion",
        start = ContractStart(ContractTrigger.None("settlement completes"), listOf("S1")),
        activities = listOf(ContractActivity.Service(id = "act-settle", name = "Settle trade", sourceIds = listOf("S1"))),
        endStates = listOf(
            ContractEndState.Signal(
                id = "end-broadcast",
                name = "Settlement broadcast",
                signalName = "settlement complete",
                sourceIds = listOf("S1"),
            ),
        ),
    )

    private fun signalDefinition(
        endEventDefinition: dev.groknull.bpmner.bpmn.BpmnEventDefinition = BpmnSignalEventDefinition("Signal_1"),
        signals: List<BpmnSignalRef> = listOf(BpmnSignalRef("Signal_1", "settlement complete")),
    ): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "Settlement broadcast",
        nodes = listOf(
            BpmnStartEvent("StartEvent_1", "Settlement completes"),
            BpmnUserTask("act-settle", "Settle trade"),
            BpmnEndEvent("end-broadcast", "Settlement broadcast", eventDefinition = endEventDefinition),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "act-settle"),
            BpmnEdge("Flow_2", "act-settle", "end-broadcast"),
        ),
        signals = signals,
    )

    private fun creditTierContract() = ProcessContract(
        id = "c-credit",
        processName = "Credit-tier routing",
        summary = "Route by credit score.",
        start = ContractStart(ContractTrigger.None("Score received"), listOf("ev1")),
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
                    ),
                    DefaultBranch(
                        id = "br-manual",
                        label = "Manual review",
                    ),
                ),
                sourceIds = listOf("ev1"),
            ),
        ),
        endStates = listOf(ContractEndState(id = "end-offer", name = "Offer generated", sourceIds = listOf("ev1"))),
        flows = listOf(
            ContractFlow.Sequence(from = "start", to = "Gateway_1"),
            ContractFlow.Branch(from = "Gateway_1", to = "Task_fast", branchId = "br-fast"),
            ContractFlow.Branch(from = "Gateway_1", to = "Task_manual", branchId = "br-manual"),
            ContractFlow.Sequence(from = "Task_fast", to = "end-offer"),
            ContractFlow.Sequence(from = "Task_manual", to = "end-offer"),
        ),
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
