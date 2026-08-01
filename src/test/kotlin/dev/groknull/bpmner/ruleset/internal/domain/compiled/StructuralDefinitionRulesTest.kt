/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnDiShape
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnSubProcess
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StructuralDefinitionRulesTest {
    @Test
    fun `duplicate sequence flows accept distinct pairs and report matching pairs`() {
        val rule = DuplicateSequenceFlowsRule()

        assertTrue(rule.evaluate(context(BpmnUserTask("a"), BpmnUserTask("b"), BpmnEdge("f1", "a", "b"))).isEmpty())

        val diagnostics = rule.evaluate(
            context(BpmnUserTask("a"), BpmnUserTask("b"), BpmnEdge("f1", "a", "b"), BpmnEdge("f2", "a", "b")),
        )
        assertEquals(setOf("f1", "f2"), diagnostics.map { it.elementId }.toSet())
    }

    @Test
    fun `scope required events accept complete subprocess and report missing events`() {
        val rule = ScopeRequiredEventsRule()

        assertTrue(
            rule.evaluate(
                context(
                    BpmnSubProcess("scope"),
                    BpmnStartEvent("start", parentRef = "scope"),
                    BpmnEndEvent("end", parentRef = "scope"),
                ),
            ).isEmpty(),
        )

        val diagnostics = rule.evaluate(context(BpmnSubProcess("scope")))
        assertEquals(
            setOf("def-scope-missing-start-event", "def-scope-missing-end-event"),
            diagnostics.map { it.diagnosticCode }.toSet(),
        )
    }

    @Test
    fun `event structure accepts valid event flows and reports cardinality and event constraints`() {
        val rule = EventStructureRule()

        assertTrue(
            rule.evaluate(
                context(
                    BpmnStartEvent("start"),
                    BpmnUserTask("task"),
                    BpmnEndEvent("end"),
                    BpmnEndEvent("terminate", eventDefinition = BpmnTerminateEventDefinition),
                    BpmnEdge("f1", "start", "task"),
                    BpmnEdge("f2", "task", "end"),
                ),
            ).isEmpty(),
        )

        val diagnostics = rule.evaluate(
            context(
                BpmnStartEvent("start"),
                BpmnEndEvent("end"),
                BpmnStartEvent("event-start", isEventSubProcessStart = true),
                BpmnEndEvent("terminate", eventDefinition = BpmnTerminateEventDefinition, parentRef = "scope"),
                BpmnEdge("into-start", "end", "start"),
                BpmnEdge("out-of-end", "end", "event-start"),
            ),
        )
        assertEquals(
            setOf(
                "evt-start-event-incoming",
                "evt-end-event-outgoing",
                "evt-event-subprocess-start-trigger",
                "evt-event-subprocess-start-incoming",
                "evt-superfluous-terminate",
            ),
            diagnostics.map { it.diagnosticCode }.toSet(),
        )
    }

    @Test
    fun `conditional flows accept exclusive gateways and report parallel gateways`() {
        val rule = ConditionalFlowRule()

        assertTrue(
            rule.evaluate(
                context(
                    BpmnExclusiveGateway("gateway"),
                    BpmnUserTask("task"),
                    BpmnEdge("flow", "gateway", "task", conditionExpression = "approved"),
                ),
            ).isEmpty(),
        )

        val diagnostics = rule.evaluate(
            context(
                BpmnParallelGateway("gateway"),
                BpmnUserTask("task"),
                BpmnEdge("flow", "gateway", "task", conditionExpression = "approved"),
            ),
        )
        assertEquals("flow-invalid-condition", diagnostics.single().diagnosticCode)
    }

    @Test
    fun `no inclusive gateway accepts other gateways and reports inclusive gateways`() {
        val rule = NoInclusiveGatewayRule()

        assertTrue(rule.evaluate(context(BpmnExclusiveGateway("gateway"))).isEmpty())

        val diagnostics = rule.evaluate(context(BpmnInclusiveGateway("gateway")))
        assertEquals("gtw-no-inclusive-gateway", diagnostics.single().diagnosticCode)
    }

    @Test
    fun `no BPMN-DI accepts definitions without diagrams and reports definitions with diagrams`() {
        val rule = NoBpmnDiRule()

        assertTrue(rule.evaluate(context(BpmnStartEvent("start"))).isEmpty())

        val definition = BpmnDefinition("P", "P", listOf(BpmnStartEvent("start")), emptyList(), diagramCount = 1)
        assertEquals("gen-no-bpmndi", rule.evaluate(BpmnDefinitionContext(definition)).single().diagnosticCode)
    }

    @Test
    fun `overlapping elements report positive area intersections but exclude touching containers and unknown shapes`() {
        val rule = NoOverlappingElementsRule()
        val definition = BpmnDefinition(
            "P",
            "P",
            listOf(BpmnUserTask("first"), BpmnUserTask("second"), BpmnSubProcess("container")),
            emptyList(),
            diShapes = mapOf(
                "first" to BpmnDiShape(0.0, 0.0, 100.0, 80.0),
                "second" to BpmnDiShape(50.0, 0.0, 100.0, 80.0),
                "container" to BpmnDiShape(0.0, 0.0, 500.0, 300.0),
                "edge-or-label" to BpmnDiShape(50.0, 0.0, 100.0, 80.0),
            ),
        )

        val diagnostics = rule.evaluate(BpmnDefinitionContext(definition))
        assertEquals(listOf("second"), diagnostics.map { it.elementId })
        assertEquals("gen-no-overlapping-elements", diagnostics.single().diagnosticCode)

        val touching = definition.copy(
            diShapes = mapOf(
                "first" to BpmnDiShape(0.0, 0.0, 100.0, 80.0),
                "second" to BpmnDiShape(100.0, 0.0, 100.0, 80.0),
            ),
        )
        assertTrue(rule.evaluate(BpmnDefinitionContext(touching)).isEmpty())
    }

    private fun context(vararg elements: Any): BpmnDefinitionContext = BpmnDefinitionContext(
        BpmnDefinition(
            "P",
            "P",
            elements.filterIsInstance<dev.groknull.bpmner.bpmn.BpmnNode>(),
            elements.filterIsInstance<BpmnEdge>(),
        ),
    )
}
