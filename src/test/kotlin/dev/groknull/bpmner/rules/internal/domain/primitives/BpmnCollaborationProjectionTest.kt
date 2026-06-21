/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength", "TooManyFunctions")

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.internal.model.BpmnDefinition
import dev.groknull.bpmner.bpmn.internal.model.BpmnEdge
import dev.groknull.bpmner.bpmn.internal.model.BpmnEndEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnLane
import dev.groknull.bpmner.bpmn.internal.model.BpmnMessageFlow
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.internal.model.BpmnParticipant
import dev.groknull.bpmner.bpmn.internal.model.BpmnStartEvent
import dev.groknull.bpmner.bpmn.internal.model.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.nlp.testBpmnNlp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end projection coverage for #196: a real [BpmnDefinition] carrying participants, lanes, and
 * message flows projects to a [PrimitiveModelContext] whose properties (poolKind, processName, role,
 * sourcePool/targetPool) and capability bits make the pool/lane/message-flow rules fire. The
 * evaluator logic itself is covered by `DeterministicPrimitivesTest`; this test guards the wiring
 * from the domain model into those evaluators.
 */
class BpmnCollaborationProjectionTest {
    private val nlp = testBpmnNlp()

    @Test
    fun `capabilities flip on only when pools or message flows are present`() {
        val plain = ctx(def()).toPrimitiveModelContext()
        assertFalse(plain.supports(ModelCapability.POOLS_AND_LANES))
        assertFalse(plain.supports(ModelCapability.MESSAGE_FLOWS))

        val collaborative = ctx(
            def(
                participants = listOf(BpmnParticipant("P_sales", "Sales", processRef = "P")),
                messageFlows = listOf(BpmnMessageFlow("mf", "Payment request", "act-1", "P_ext")),
            ),
        ).toPrimitiveModelContext()
        assertTrue(collaborative.supports(ModelCapability.POOLS_AND_LANES))
        assertTrue(collaborative.supports(ModelCapability.MESSAGE_FLOWS))
    }

    @Test
    fun `white-box pool projects poolKind and processName, firing when the label differs from the process`() {
        val drift = ctx(def(participants = listOf(BpmnParticipant("P_main", "Wrong label", processRef = "P"))))
        val element = drift.toPrimitiveModelContext().elements.single { it.id == "P_main" }
        assertEquals("WHITE_BOX", element.property("poolKind"))
        assertEquals("Process", element.property("processName"))

        assertEquals(listOf("P_main"), whiteBox(drift))
        val aligned = ctx(def(participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P"))))
        assertTrue(whiteBox(aligned).isEmpty(), "a pool named after its process is not flagged")
    }

    @Test
    fun `black-box pool fires only when it has no label`() {
        val blank = ctx(def(participants = listOf(BpmnParticipant("P_ext", name = null, processRef = null))))
        assertEquals(listOf("P_ext"), blackBox(blank))
        val named = ctx(def(participants = listOf(BpmnParticipant("P_ext", "Payment Provider", processRef = null))))
        assertTrue(blackBox(named).isEmpty())
    }

    @Test
    fun `lane projects its label as role, firing when the lane is unlabelled`() {
        val unlabelled = ctx(
            def(
                participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P")),
                lanes = listOf(BpmnLane("Lane_blank", name = null, participantId = "P_main", flowNodeRefs = listOf("act-1"))),
            ),
        )
        assertEquals(listOf("Lane_blank"), lane(unlabelled))
        val labelled = ctx(
            def(
                participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P")),
                lanes = listOf(BpmnLane("Lane_sales", "Sales", participantId = "P_main", flowNodeRefs = listOf("act-1"))),
            ),
        )
        assertEquals("Sales", labelled.toPrimitiveModelContext().elements.single { it.id == "Lane_sales" }.property("role"))
        assertTrue(lane(labelled).isEmpty())
    }

    @Test
    fun `message flow within one pool fires ACROSS_POOLS, but a cross-pool flow does not`() {
        val withinPool = ctx(
            def(
                participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P")),
                messageFlows = listOf(BpmnMessageFlow("mf", "Note", "act-1", "EndEvent_1")),
            ),
        )
        assertEquals(listOf("mf"), acrossPools(withinPool))

        val crossPool = ctx(
            def(
                participants = listOf(
                    BpmnParticipant("P_main", "Process", processRef = "P"),
                    BpmnParticipant("P_ext", "Payment Provider", processRef = null),
                ),
                messageFlows = listOf(BpmnMessageFlow("mf", "Payment request", "act-1", "P_ext")),
            ),
        )
        assertTrue(acrossPools(crossPool).isEmpty(), "a flow that crosses pool boundaries is valid")
    }

    @Test
    fun `message flow named with a leading verb is flagged, a noun message name is not`() {
        val action = ctx(
            def(
                participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P")),
                messageFlows = listOf(BpmnMessageFlow("mf", "Send approval", "act-1", "P_ext")),
            ),
        )
        assertEquals(listOf("mf"), messageName(action))

        val noun = ctx(
            def(
                participants = listOf(BpmnParticipant("P_main", "Process", processRef = "P")),
                messageFlows = listOf(BpmnMessageFlow("mf", "Approval confirmation", "act-1", "P_ext")),
            ),
        )
        assertTrue(messageName(noun).isEmpty())
    }

    private fun whiteBox(ctx: BpmnDefinitionContext) = PoolLabelCheck().evaluate(ctx, metadata("white-box", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS)).map { it.elementId }

    private fun blackBox(ctx: BpmnDefinitionContext) = PoolLabelCheck().evaluate(ctx, metadata("black-box", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.BLACK_BOX_NAMED_BY_EXTERNAL_ENTITY_OR_PROCESS)).map { it.elementId }

    private fun lane(ctx: BpmnDefinitionContext) = PoolLabelCheck().evaluate(ctx, metadata("lane", "bpmn:Lane"), PoolLabelCheckConfig(PoolLabelMode.LANE_LABELS_BUSINESS_ROLES_PERFORMERS)).map { it.elementId }

    private fun acrossPools(ctx: BpmnDefinitionContext) = ConnectivityCheck().evaluate(ctx, metadata("across-pools", "bpmn:MessageFlow"), ConnectivityCheckConfig(ConnectivityMode.ACROSS_POOLS)).map { it.elementId }

    private fun messageName(ctx: BpmnDefinitionContext) = PartOfSpeechCheck(nlp).evaluate(ctx, metadata("message-name", "bpmn:MessageFlow"), PartOfSpeechCheckConfig("name", PartOfSpeechMode.LEADING_MUST_NOT_BE, NlpPosTag.VERB)).map { it.elementId }

    private fun ctx(definition: BpmnDefinition) = BpmnDefinitionContext(definition)

    private fun def(
        participants: List<BpmnParticipant> = emptyList(),
        lanes: List<BpmnLane> = emptyList(),
        messageFlows: List<BpmnMessageFlow> = emptyList(),
    ): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "Process",
        nodes = baseNodes(),
        sequences = listOf(BpmnEdge("f1", "StartEvent_1", "act-1"), BpmnEdge("f2", "act-1", "EndEvent_1")),
        participants = participants,
        lanes = lanes,
        messageFlows = messageFlows,
    )

    private fun baseNodes(): List<BpmnNode> = listOf(
        BpmnStartEvent("StartEvent_1", "Start"),
        BpmnUserTask("act-1", "Do work"),
        BpmnEndEvent("EndEvent_1", "Done"),
    )
}
