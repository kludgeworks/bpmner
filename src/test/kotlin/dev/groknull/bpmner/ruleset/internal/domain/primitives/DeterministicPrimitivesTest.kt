/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength", "TooManyFunctions")

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnBoundaryEvent
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnGroup
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerKind
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeterministicPrimitivesTest {
    @Test
    fun `presence check emits one diagnostic per targeted group`() {
        val ctx = context(
            nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")),
            groups = listOf(BpmnGroup("g1", "Review"), BpmnGroup("g2")),
        )

        val diagnostics = PresenceCheck().evaluate(ctx, metadata("group-presence", BpmnTypeName.GROUP), PresenceCheckConfig)

        assertEquals(listOf("g1", "g2"), diagnostics.map { it.elementId })
    }

    @Test
    fun `groups project as exact primitive artifacts and not flow nodes`() {
        val model = context(
            nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")),
            groups = listOf(BpmnGroup("g1", "Review")),
        ).toPrimitiveModelContext()

        val group = model.elements.single { it.id == "g1" }
        assertEquals(BpmnTypeName.GROUP, group.typeName)
        assertEquals("Review", group.property("name"))
        assertTrue(metadata("flow-node", BpmnTypeName.FLOW_NODE).targetedElements(model).none { it.id == "g1" })
    }

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

    // PropertyPatternCheck coverage lives in `PropertyPatternCheckTest` — extracted per the
    // existing per-primitive pattern (`CompositeCheckTest`, `NlpPrimitivesTest`).

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
    fun `required association flags targeted elements missing their association`() {
        val model = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.ASSOCIATIONS),
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
    fun `required association is dormant when the model does not advertise ASSOCIATIONS capability`() {
        // Same fixture as above but without the capability bit — what production looks like
        // until #196 lands association support.
        val model = PrimitiveModelContext(
            elements = listOf(
                PrimitiveElement("task", "bpmn:Task"),
                PrimitiveElement("other", "bpmn:Task"),
            ),
        )

        assertTrue(
            RequiredAssociationCheck().evaluate(
                model,
                metadata("association", "bpmn:Task"),
                RequiredAssociationCheckConfig("bpmn:Association", targetTypes = listOf("bpmn:TextAnnotation")),
            ).isEmpty(),
            "Dormant primitive must not fire without ASSOCIATIONS capability",
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

        val poolAware = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.POOLS_AND_LANES),
            elements = emptyList(),
            sequenceFlows = listOf(PrimitiveFlow("sf", "a", "b", sourcePool = "p1", targetPool = "p2")),
            messageFlows = listOf(PrimitiveFlow("mf", "a", "b", name = "message", sourcePool = "p1", targetPool = "p1")),
        )
        assertEquals(listOf("sf"), check.evaluate(poolAware, metadata("within", "bpmn:SequenceFlow"), ConnectivityCheckConfig(ConnectivityMode.WITHIN_POOL)).map { it.elementId })
        assertEquals(listOf("mf"), check.evaluate(poolAware, metadata("across", "bpmn:MessageFlow"), ConnectivityCheckConfig(ConnectivityMode.ACROSS_POOLS)).map { it.elementId })
    }

    @Test
    fun `connectivity pool modes are dormant without POOLS_AND_LANES capability`() {
        // Same data shape as above but no capability bit — a production context.
        val production = PrimitiveModelContext(
            elements = emptyList(),
            sequenceFlows = listOf(PrimitiveFlow("sf", "a", "b", sourcePool = "p1", targetPool = "p2")),
            messageFlows = listOf(PrimitiveFlow("mf", "a", "b", name = "message", sourcePool = "p1", targetPool = "p1")),
        )
        val check = ConnectivityCheck()

        assertTrue(check.evaluate(production, metadata("within", "bpmn:SequenceFlow"), ConnectivityCheckConfig(ConnectivityMode.WITHIN_POOL)).isEmpty())
        assertTrue(check.evaluate(production, metadata("across", "bpmn:MessageFlow"), ConnectivityCheckConfig(ConnectivityMode.ACROSS_POOLS)).isEmpty())
    }

    @Test
    fun `pairing handles error end boundary plus link and message-start pairings`() {
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

        val messageAware = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.MESSAGE_FLOWS),
            elements = listOf(
                PrimitiveElement("throw", "bpmn:IntermediateThrowEvent", mapOf("eventDefinition" to "LINK", "linkRef" to "L1")),
                PrimitiveElement("msg", "bpmn:StartEvent", mapOf("eventDefinition" to "MESSAGE", "messageRef" to "M1")),
            ),
            messageFlows = listOf(PrimitiveFlow("mf", "external", "other", name = "Message")),
        )
        assertEquals(listOf("throw"), check.evaluate(messageAware, metadata("link", "bpmn:Event"), PairingCheckConfig(PairingMode.LINK_PAIRING)).map { it.elementId })
        assertEquals(listOf("msg"), check.evaluate(messageAware, metadata("message", "bpmn:StartEvent"), PairingCheckConfig(PairingMode.MESSAGE_START_FLOW)).map { it.elementId })
    }

    @Test
    fun `pairing MESSAGE_START_FLOW is dormant without MESSAGE_FLOWS capability`() {
        val production = PrimitiveModelContext(
            elements = listOf(
                PrimitiveElement("msg", "bpmn:StartEvent", mapOf("eventDefinition" to "MESSAGE", "messageRef" to "M1")),
            ),
        )
        assertTrue(
            PairingCheck().evaluate(
                production,
                metadata("message", "bpmn:StartEvent"),
                PairingCheckConfig(PairingMode.MESSAGE_START_FLOW),
            ).isEmpty(),
            "Dormant primitive must not flag message starts when MESSAGE_FLOWS is absent",
        )
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
    fun `pool labels are dormant until pools and lanes are in the model`() {
        val check = PoolLabelCheck()
        val production = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")))
        assertTrue(check.evaluate(production, metadata("pool", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS)).isEmpty())

        val poolAware = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.POOLS_AND_LANES),
            elements = listOf(
                PrimitiveElement("pool", "bpmn:Participant", mapOf("poolKind" to "WHITE_BOX", "name" to "Sales", "processName" to "Fulfil order")),
                PrimitiveElement("lane", "bpmn:Lane", mapOf("name" to "Team")),
            ),
        )
        assertEquals(listOf("pool"), check.evaluate(poolAware, metadata("pool", "bpmn:Participant"), PoolLabelCheckConfig(PoolLabelMode.WHITE_BOX_NAMED_BY_PROCESS)).map { it.elementId })
        assertEquals(listOf("lane"), check.evaluate(poolAware, metadata("lane", "bpmn:Lane"), PoolLabelCheckConfig(PoolLabelMode.LANE_LABELS_BUSINESS_ROLES_PERFORMERS)).map { it.elementId })
    }

    @Test
    fun `element constraint subset timer and parallel modes flag the right elements`() {
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
    }

    @Test
    fun `event-based gateway flags non-event non-receive-task targets`() {
        // EventBasedGateway pointing at a plain task — invalid per BPMN 2.0 §10.5.4.6.
        val model = PrimitiveModelContext(
            elements = listOf(
                PrimitiveElement("eg", "bpmn:EventBasedGateway"),
                PrimitiveElement("task", "bpmn:Task"),
            ),
            sequenceFlows = listOf(PrimitiveFlow("f", "eg", "task")),
        )
        assertEquals(
            listOf("eg"),
            ElementConstraintCheck().evaluate(
                model,
                metadata("event-based", "bpmn:EventBasedGateway"),
                ElementConstraintCheckConfig("bpmn:EventBasedGateway", ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS),
            ).map { it.elementId },
        )
    }

    @Test
    fun `event-based gateway accepts a ReceiveTask target per BPMN 2 dot 0`() {
        // EventBasedGateway pointing at a ReceiveTask — valid per BPMN 2.0 §10.5.4.6.
        val model = PrimitiveModelContext(
            elements = listOf(
                PrimitiveElement("eg", "bpmn:EventBasedGateway"),
                PrimitiveElement("rt", "bpmn:ReceiveTask"),
            ),
            sequenceFlows = listOf(PrimitiveFlow("f", "eg", "rt")),
        )
        assertTrue(
            ElementConstraintCheck().evaluate(
                model,
                metadata("event-based", "bpmn:EventBasedGateway"),
                ElementConstraintCheckConfig("bpmn:EventBasedGateway", ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS),
            ).isEmpty(),
            "ReceiveTask is a valid event-based gateway target per BPMN 2.0 §10.5.4.6",
        )
    }

    @Test
    fun `property pattern emits rule-config-error on malformed regex`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "name"), BpmnEndEvent("e", "End")))
        val diagnostics = PropertyPatternCheck().evaluate(
            ctx,
            metadata("bad-pattern", "bpmn:UserTask"),
            PropertyPatternCheckConfig("name", "[unclosed", "explain"),
        )

        assertEquals(1, diagnostics.size)
        val diag = diagnostics.single()
        assertEquals("rule-config-error", diag.diagnosticCode)
        assertEquals(dev.groknull.bpmner.bpmn.RuleSeverity.ERROR, diag.severity)
        assertTrue(diag.message.contains("[unclosed"), "config-error message should quote the offending pattern: ${diag.message}")
    }

    @Test
    fun `element constraint timer expression emits rule-config-error on malformed regex`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start", BpmnTimerEventDefinition(BpmnTimerKind.DATE, "PT5M")),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = ElementConstraintCheck().evaluate(
            ctx,
            metadata("bad-timer-pattern", "bpmn:StartEvent"),
            ElementConstraintCheckConfig(
                "bpmn:StartEvent",
                ElementConstraintMode.TIMER_EXPRESSION,
                mapOf("pattern" to "(?<malformed"),
            ),
        )

        assertEquals("rule-config-error", diagnostics.single().diagnosticCode)
    }

    @Test
    fun `unsupported gateway target names are skipped for production evaluation`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")))

        assertTrue(RequiredPropertyCheck().evaluate(ctx, metadata("inclusive", "bpmn:InclusiveGateway"), RequiredPropertyCheckConfig("name")).isEmpty())
        assertTrue(TopologyCheck().evaluate(ctx, metadata("complex", "bpmn:ComplexGateway"), TopologyCheckConfig(TopologyMode.NO_SUPERFLUOUS)).isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Phase 2H.1 top-ups — one extra @Test per primitive that previously had only one case,
    // bringing each to ≥3 cases. See #245.

    @Test
    fun `required property fires on each blank element among many targeted instances`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("a", "Review submission"),
                BpmnUserTask("b"),
                BpmnUserTask("c", "Approve"),
                BpmnUserTask("d"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = RequiredPropertyCheck().evaluate(
            ctx,
            metadata("name", "bpmn:UserTask"),
            RequiredPropertyCheckConfig("name"),
        )
        assertEquals(listOf("b", "d"), diagnostics.map { it.elementId })
    }

    @Test
    fun `required property checks non-name properties via PrimitiveModelContext`() {
        // The BPMN node mapping exposes only well-known fields; PrimitiveElement.properties
        // is the route used when a Pkl rule names a property the mapping does not surface.
        val model = PrimitiveModelContext(
            elements = listOf(
                PrimitiveElement("e1", "bpmn:Task", mapOf("description" to "Order")),
                PrimitiveElement("e2", "bpmn:Task"),
            ),
        )
        val diagnostics = RequiredPropertyCheck().evaluate(
            model,
            metadata("description", "bpmn:Task"),
            RequiredPropertyCheckConfig("description"),
        )
        assertEquals(listOf("e2"), diagnostics.map { it.elementId })
    }

    @Test
    fun `vocabulary REQUIRE_LEADING fires when the leading token is not in the vocab`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("verb", "Process the order"),
                BpmnUserTask("noun", "Order processing"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = VocabularyCheck().evaluate(
            ctx,
            metadata("leading-verb", "bpmn:UserTask"),
            VocabularyCheckConfig("name", VocabularyMode.REQUIRE_LEADING, listOf("process", "approve", "send")),
        )
        assertEquals(listOf("noun"), diagnostics.map { it.elementId })
    }

    @Test
    fun `vocabulary FORBID_LEADING fires only when the leading token is in the vocab`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("role-led", "Manager approval"),
                BpmnUserTask("verb-led", "Submit to manager"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = VocabularyCheck().evaluate(
            ctx,
            metadata("no-leading-role", "bpmn:UserTask"),
            VocabularyCheckConfig("name", VocabularyMode.FORBID_LEADING, listOf("manager", "team")),
        )
        assertEquals(listOf("role-led"), diagnostics.map { it.elementId })
    }

    @Test
    fun `cardinality fires when count exceeds max`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s1", "Start 1"),
                BpmnStartEvent("s2", "Start 2"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = CardinalityCheck().evaluate(
            ctx,
            metadata("at-most-one-start", "bpmn:StartEvent"),
            CardinalityCheckConfig("bpmn:StartEvent", max = 1),
        )
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun `cardinality stays silent when count is inside the min and max range`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnEndEvent("e1", "End 1"),
                BpmnEndEvent("e2", "End 2"),
            ),
        )
        val diagnostics = CardinalityCheck().evaluate(
            ctx,
            metadata("ends-bounded", "bpmn:EndEvent"),
            CardinalityCheckConfig("bpmn:EndEvent", min = 1, max = 3),
        )
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `pool labels flag black-box participants without a label`() {
        val poolAware = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.POOLS_AND_LANES),
            elements = listOf(
                PrimitiveElement("blank", "bpmn:Participant", mapOf("poolKind" to "BLACK_BOX", "name" to "")),
                PrimitiveElement("named", "bpmn:Participant", mapOf("poolKind" to "BLACK_BOX", "name" to "Acme Corp")),
            ),
        )
        val diagnostics = PoolLabelCheck().evaluate(
            poolAware,
            metadata("black-box-label", "bpmn:Participant"),
            PoolLabelCheckConfig(PoolLabelMode.BLACK_BOX_NAMED_BY_EXTERNAL_ENTITY_OR_PROCESS),
        )
        assertEquals(listOf("blank"), diagnostics.map { it.elementId })
    }

    @Test
    fun `pool labels flag child-diagram participants whose name diverges from the process name`() {
        val poolAware = PrimitiveModelContext(
            supportedCapabilities = setOf(ModelCapability.POOLS_AND_LANES),
            elements = listOf(
                PrimitiveElement(
                    "ok",
                    "bpmn:Participant",
                    mapOf("isChildDiagram" to "true", "name" to "Fulfil order", "processName" to "Fulfil order"),
                ),
                PrimitiveElement(
                    "drift",
                    "bpmn:Participant",
                    mapOf("isChildDiagram" to "true", "name" to "Sales", "processName" to "Fulfil order"),
                ),
            ),
        )
        val diagnostics = PoolLabelCheck().evaluate(
            poolAware,
            metadata("child-diagram-label", "bpmn:Participant"),
            PoolLabelCheckConfig(PoolLabelMode.CHILD_DIAGRAMS_KEEP_POOL_PROCESS_NAME),
        )
        assertEquals(listOf("drift"), diagnostics.map { it.elementId })
    }
}
