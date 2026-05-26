/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength", "TooManyFunctions")

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeterministicPrimitivesTest {
    @Test
    fun `required property flags only matched blank properties`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t"), BpmnEndEvent("e", "End")))
        val diagnostics = RequiredPropertyCheck().evaluate(ctx, metadata("name", "bpmn:UserTask"), RequiredPropertyCheckConfig("name"))

        assertEquals(listOf("t"), diagnostics.map { it.elementId })
        assertTrue(
            RequiredPropertyCheck().evaluate(
                ctx,
                metadata("unsupported", "bpmn:InclusiveGateway"),
                RequiredPropertyCheckConfig("name"),
            ).isEmpty(),
        )
    }

    @Test
    fun `property pattern skips blank values and flags non-matching values`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("bad", "approve request"), BpmnEndEvent("e")))
        val check = PropertyPatternCheck()

        assertEquals(
            listOf("bad"),
            check.evaluate(
                ctx,
                metadata("pattern", "bpmn:UserTask"),
                PropertyPatternCheckConfig("name", "^[A-Z].*", "sentence case"),
            ).map { it.elementId },
        )
        assertTrue(check.evaluate(ctx, metadata("blank", "bpmn:EndEvent"), PropertyPatternCheckConfig("name", "^[A-Z].*")).isEmpty())
    }

    @Test
    fun `vocabulary supports require and forbid modes case-insensitively`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "Execute onboarding"), BpmnEndEvent("e", "End")))
        val metadata = metadata("vocabulary", "bpmn:UserTask")
        val check = VocabularyCheck()

        assertEquals(
            listOf("t"),
            check.evaluate(ctx, metadata, VocabularyCheckConfig("name", VocabularyMode.FORBID, listOf("execute"))).map { it.elementId },
        )
        assertTrue(check.evaluate(ctx, metadata, VocabularyCheckConfig("name", VocabularyMode.REQUIRE, listOf("onboarding"))).isEmpty())
        assertEquals(
            listOf("t"),
            check.evaluate(ctx, metadata, VocabularyCheckConfig("name", VocabularyMode.REQUIRE, listOf("approve"))).map { it.elementId },
        )
    }

    @Test
    fun `required association is covered synthetically`() {
        val model = PrimitiveModelContext(
            synthetic = true,
            elements = listOf(
                PrimitiveElement("task", "bpmn:Task"),
                PrimitiveElement("note", "bpmn:TextAnnotation"),
                PrimitiveElement("other", "bpmn:Task"),
            ),
            associations = listOf(PrimitiveAssociation("a1", "task", "note")),
        )
        val check = RequiredAssociationCheck()

        assertEquals(
            listOf("other"),
            check.evaluate(
                model,
                metadata("association", "bpmn:Task"),
                RequiredAssociationCheckConfig("bpmn:Association", targetTypes = listOf("bpmn:TextAnnotation")),
            ).map { it.elementId },
        )
    }

    @Test
    fun `topology detects fake joins and can query gateway topology while reporting tasks`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("a", "A"),
                BpmnUserTask("b", "B"),
                BpmnUserTask("fake", "Continue"),
                BpmnExclusiveGateway("join"),
                BpmnUserTask("after", "After"),
                BpmnEndEvent("e", "End"),
            ),
            edges = listOf(
                BpmnEdge("f1", "s", "a"),
                BpmnEdge("f2", "s", "b"),
                BpmnEdge("f3", "a", "fake"),
                BpmnEdge("f4", "b", "fake"),
                BpmnEdge("f5", "a", "join"),
                BpmnEdge("f6", "b", "join"),
                BpmnEdge("f7", "join", "after"),
                BpmnEdge("f8", "after", "e"),
            ),
        )

        val diagnostics = TopologyCheck().evaluate(
            ctx,
            metadata("fake-join", "bpmn:Task"),
            TopologyCheckConfig(TopologyMode.NO_FAKE_JOIN),
        )

        assertEquals(listOf("fake"), diagnostics.map { it.elementId })
    }

    @Test
    fun `topology gateway modes detect superfluous join-fork and named convergence`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("a", "A"),
                BpmnUserTask("b", "B"),
                BpmnExclusiveGateway("super"),
                BpmnParallelGateway("both"),
                BpmnExclusiveGateway("join", "Named join"),
                BpmnEndEvent("e", "End"),
            ),
            edges = listOf(
                BpmnEdge("f1", "s", "super"),
                BpmnEdge("f2", "super", "a"),
                BpmnEdge("f3", "a", "both"),
                BpmnEdge("f4", "b", "both"),
                BpmnEdge("f5", "both", "join"),
                BpmnEdge("f6", "both", "e"),
                BpmnEdge("f7", "a", "join"),
                BpmnEdge("f8", "join", "e"),
            ),
        )
        val metadata = metadata("topology", "bpmn:ExclusiveGateway", "bpmn:ParallelGateway")
        val check = TopologyCheck()

        assertEquals(listOf("super"), check.evaluate(ctx, metadata, TopologyCheckConfig(TopologyMode.NO_SUPERFLUOUS)).map { it.elementId })
        assertEquals(listOf("both"), check.evaluate(ctx, metadata, TopologyCheckConfig(TopologyMode.NO_JOIN_FORK)).map { it.elementId })
        assertEquals(listOf("join"), check.evaluate(ctx, metadata, TopologyCheckConfig(TopologyMode.CONVERGING_UNNAMED)).map { it.elementId })
    }

    @Test
    fun `connectivity handles no incoming named flows and pool semantics`() {
        val ctx = context(
            nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "Task"), BpmnEndEvent("e", "End")),
            edges = listOf(BpmnEdge("f1", "t", "s"), BpmnEdge("f2", "t", "e", name = "done")),
        )
        val check = ConnectivityCheck()

        assertEquals(listOf("s"), check.evaluate(ctx, metadata("incoming", "bpmn:StartEvent"), ConnectivityCheckConfig(ConnectivityMode.NO_INCOMING)).map { it.elementId })
        assertEquals(listOf("f1"), check.evaluate(ctx, metadata("flows", "bpmn:SequenceFlow"), ConnectivityCheckConfig(ConnectivityMode.FLOWS_NAMED)).map { it.elementId })

        val synthetic = PrimitiveModelContext(
            synthetic = true,
            elements = emptyList(),
            sequenceFlows = listOf(PrimitiveFlow("sf", "a", "b", sourcePool = "p1", targetPool = "p2")),
            messageFlows = listOf(PrimitiveFlow("mf", "a", "b", name = "message", sourcePool = "p1", targetPool = "p1")),
        )
        assertEquals(listOf("sf"), check.evaluate(synthetic, metadata("within", "bpmn:SequenceFlow"), ConnectivityCheckConfig(ConnectivityMode.WITHIN_POOL)).map { it.elementId })
        assertEquals(listOf("mf"), check.evaluate(synthetic, metadata("across", "bpmn:MessageFlow"), ConnectivityCheckConfig(ConnectivityMode.ACROSS_POOLS)).map { it.elementId })
    }

    @Test
    fun `pairing handles error end boundary and synthetic link and message starts`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("t", "Task"),
                BpmnBoundaryEvent("boundary", attachedToRef = "t", eventDefinition = BpmnErrorEventDefinition("known")),
                BpmnEndEvent("known-end", eventDefinition = BpmnErrorEventDefinition("known")),
                BpmnEndEvent("missing-end", eventDefinition = BpmnErrorEventDefinition("missing")),
            ),
            edges = listOf(BpmnEdge("f1", "s", "t"), BpmnEdge("f2", "t", "known-end"), BpmnEdge("f3", "t", "missing-end")),
        )
        val check = PairingCheck()

        assertEquals(
            listOf("missing-end"),
            check.evaluate(ctx, metadata("error-pair", "bpmn:EndEvent"), PairingCheckConfig(PairingMode.ERROR_END_BOUNDARY)).map { it.elementId },
        )

        val synthetic = PrimitiveModelContext(
            synthetic = true,
            elements = listOf(
                PrimitiveElement("throw", "bpmn:IntermediateThrowEvent", mapOf("eventDefinition" to "LINK", "linkRef" to "L1")),
                PrimitiveElement("msg", "bpmn:StartEvent", mapOf("eventDefinition" to "MESSAGE", "messageRef" to "M1")),
            ),
            messageFlows = listOf(PrimitiveFlow("mf", "external", "other", name = "Message")),
        )
        assertEquals(listOf("throw"), check.evaluate(synthetic, metadata("link", "bpmn:Event"), PairingCheckConfig(PairingMode.LINK_PAIRING)).map { it.elementId })
        assertEquals(listOf("msg"), check.evaluate(synthetic, metadata("message", "bpmn:StartEvent"), PairingCheckConfig(PairingMode.MESSAGE_START_FLOW)).map { it.elementId })
    }

    @Test
    fun `cardinality counts matched elements and ignores unsupported production targets`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")))
        val check = CardinalityCheck()

        assertTrue(
            check.evaluate(
                ctx,
                metadata("one-start", "bpmn:StartEvent"),
                CardinalityCheckConfig("bpmn:StartEvent", min = 1, max = 1),
            ).isEmpty(),
        )
        assertEquals(1, check.evaluate(ctx, metadata("two-starts", "bpmn:StartEvent"), CardinalityCheckConfig("bpmn:StartEvent", min = 2)).size)
        assertTrue(check.evaluate(ctx, metadata("unsupported", "bpmn:ComplexGateway"), CardinalityCheckConfig("bpmn:ComplexGateway", min = 1)).isEmpty())
    }

    @Test
    fun `pool labels are synthetic until pools and lanes are in the model`() {
        val check = PoolLabelCheck()
        val production = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")))
        assertTrue(check.evaluate(production, metadata("pool", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS)).isEmpty())

        val synthetic = PrimitiveModelContext(
            synthetic = true,
            elements = listOf(
                PrimitiveElement("pool", "bpmn:Participant", mapOf("poolKind" to "WHITE_BOX", "name" to "Sales", "processName" to "Fulfil order")),
                PrimitiveElement("lane", "bpmn:Lane", mapOf("name" to "Team")),
            ),
        )
        assertEquals(listOf("pool"), check.evaluate(synthetic, metadata("pool", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS)).map { it.elementId })
        assertEquals(listOf("lane"), check.evaluate(synthetic, metadata("lane", "bpmn:Lane"), PoolLabelCheckConfig(PoolLabelMode.LANE_LABELS_BUSINESS_ROLES_PERFORMERS)).map { it.elementId })
    }

    @Test
    fun `element constraints cover subset timer parallel and synthetic event-based gateways`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start", BpmnTimerEventDefinition(BpmnTimerKind.DATE, "")),
                BpmnUserTask("t", "Task"),
                BpmnParallelGateway("pg"),
                BpmnUserTask("a", "A"),
                BpmnUserTask("b", "B"),
                BpmnEndEvent("e", "End"),
            ),
            edges = listOf(
                BpmnEdge("f1", "s", "pg"),
                BpmnEdge("f2", "t", "pg"),
                BpmnEdge("f3", "pg", "a"),
                BpmnEdge("f4", "pg", "b"),
                BpmnEdge("f5", "a", "e"),
            ),
        )
        val check = ElementConstraintCheck()

        assertEquals(
            listOf("pg"),
            check.evaluate(
                ctx,
                metadata("subset", "bpmn:FlowNode"),
                ElementConstraintCheckConfig("bpmn:FlowNode", ElementConstraintMode.ALLOWED_ELEMENT_SUBSET, mapOf("allowed" to listOf("bpmn:Event", "bpmn:Task"))),
            ).map { it.elementId },
        )
        assertEquals(listOf("s"), check.evaluate(ctx, metadata("timer", "bpmn:StartEvent"), ElementConstraintCheckConfig("bpmn:StartEvent", ElementConstraintMode.TIMER_EXPRESSION)).map { it.elementId })
        assertEquals(listOf("pg"), check.evaluate(ctx, metadata("parallel", "bpmn:ParallelGateway"), ElementConstraintCheckConfig("bpmn:ParallelGateway", ElementConstraintMode.PARALLEL_GATEWAY_STRUCTURE)).map { it.elementId })

        val synthetic = PrimitiveModelContext(
            synthetic = true,
            elements = listOf(
                PrimitiveElement("eg", "bpmn:EventBasedGateway"),
                PrimitiveElement("task", "bpmn:Task"),
            ),
            sequenceFlows = listOf(PrimitiveFlow("f", "eg", "task")),
        )
        assertEquals(
            listOf("eg"),
            check.evaluate(
                synthetic,
                metadata("event-based", "bpmn:EventBasedGateway"),
                ElementConstraintCheckConfig("bpmn:EventBasedGateway", ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS),
            ).map { it.elementId },
        )
    }

    @Test
    fun `unsupported gateway target names are skipped for production evaluation`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")))

        assertTrue(RequiredPropertyCheck().evaluate(ctx, metadata("inclusive", "bpmn:InclusiveGateway"), RequiredPropertyCheckConfig("name")).isEmpty())
        assertTrue(TopologyCheck().evaluate(ctx, metadata("complex", "bpmn:ComplexGateway"), TopologyCheckConfig(TopologyMode.NO_SUPERFLUOUS)).isEmpty())
    }

    private fun metadata(id: String, vararg targetElements: String): RuleMetadata = RuleMetadata(
        id = id,
        name = id,
        slug = id,
        category = "Test",
        intent = "Test rule.",
        forModellers = "Test rule.",
        forAI = "Test rule.",
        targetElements = targetElements.toList(),
        errorMessages = mapOf("default" to "$id violation"),
        severity = RuleSeverity.ERROR,
    )

    private fun context(
        nodes: List<dev.groknull.bpmner.core.BpmnNode>,
        edges: List<BpmnEdge>? = null,
    ): BpmnDefinitionContext {
        val actualEdges = edges ?: nodes.zipWithNext().mapIndexed { index, (source, target) ->
            BpmnEdge("f${index + 1}", source.id, target.id)
        }
        return BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = nodes,
                sequences = actualEdges.ifEmpty { listOf(BpmnEdge("f", nodes.first().id, nodes.last().id)) },
            ),
        )
    }
}
