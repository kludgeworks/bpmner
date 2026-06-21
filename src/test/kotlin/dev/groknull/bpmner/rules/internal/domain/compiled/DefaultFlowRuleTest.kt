/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DefaultFlowRuleTest {
    private val rule = DefaultFlowRule()

    @Test
    fun `default flow on a parallel gateway emits def-default-flow-non-gateway`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s"),
                        BpmnParallelGateway(id = "pgw"),
                        BpmnUserTask(id = "a", name = "A"),
                        BpmnEndEvent(id = "e"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "pgw"),
                        BpmnEdge(id = "f-default", sourceRef = "pgw", targetRef = "a", isDefault = true),
                        BpmnEdge(id = "f3", sourceRef = "a", targetRef = "e"),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single()
        assertEquals("def-default-flow-non-gateway", diag.diagnosticCode)
        assertEquals("def-default-flows", diag.ruleId)
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals(
            "edge f-default isDefault is only valid when sourceRef points to an" +
                " EXCLUSIVE_GATEWAY or INCLUSIVE_GATEWAY",
            diag.message,
        )
        assertEquals("f-default", diag.elementId)
    }

    @Test
    fun `orphan default flow whose sourceRef matches no node still fires def-default-flow-non-gateway`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e"),
                        BpmnEdge(id = "f-orphan", sourceRef = "missing", targetRef = "e", isDefault = true),
                    ),
                ),
            )

        val diag = rule.evaluate(ctx).single { it.diagnosticCode == "def-default-flow-non-gateway" }
        assertEquals("f-orphan", diag.elementId)
    }

    @Test
    fun `default flow on an inclusive gateway emits no diagnostic`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s"),
                        BpmnInclusiveGateway(id = "igw", name = "Which add-ons?"),
                        BpmnUserTask(id = "a", name = "Wrap"),
                        BpmnUserTask(id = "b", name = "Skip"),
                        BpmnEndEvent(id = "e"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "igw"),
                        BpmnEdge(id = "fa", sourceRef = "igw", targetRef = "a", conditionExpression = "wrap?"),
                        BpmnEdge(id = "fb", sourceRef = "igw", targetRef = "b", isDefault = true),
                        BpmnEdge(id = "fae", sourceRef = "a", targetRef = "e"),
                        BpmnEdge(id = "fbe", sourceRef = "b", targetRef = "e"),
                    ),
                ),
            )

        val diagnostics = rule.evaluate(ctx)
        assertTrue(
            diagnostics.none { it.diagnosticCode == "def-default-flow-non-gateway" },
            "default flow on inclusive gateway should not fire def-default-flow-non-gateway; got $diagnostics",
        )
    }

    @Test
    fun `multiple default flows on one source emit def-multiple-default-flows`() {
        val ctx = twoPathGatewayCtx(
            edgeFa = BpmnEdge(id = "fa", sourceRef = "gw", targetRef = "a", isDefault = true),
            edgeFb = BpmnEdge(id = "fb", sourceRef = "gw", targetRef = "b", isDefault = true),
        )
        val diag = rule.evaluate(ctx).single { it.diagnosticCode == "def-multiple-default-flows" }
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("node gw has 2 default flows (fa, fb); at most one is allowed", diag.message)
        assertEquals("gw", diag.elementId)
    }

    @Test
    fun `single default flow on an exclusive gateway emits no diagnostic`() {
        val ctx = twoPathGatewayCtx(
            edgeFa = BpmnEdge(id = "fa", sourceRef = "gw", targetRef = "a", isDefault = true),
            edgeFb = BpmnEdge(id = "fb", sourceRef = "gw", targetRef = "b", conditionExpression = "x"),
        )
        assertTrue(rule.evaluate(ctx).isEmpty())
    }

    @Test
    fun `no default flows anywhere emits no diagnostic`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "P",
                    nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
                    sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e")),
                ),
            )

        assertTrue(rule.evaluate(ctx).isEmpty())
    }

    /**
     * Builds a [BpmnDefinitionContext] with a simple exclusive-gateway graph:
     * start → gw → {a, b} → end, with [edgeFa] and [edgeFb] as the outbound gateway edges.
     */
    private fun twoPathGatewayCtx(
        edgeFa: BpmnEdge,
        edgeFb: BpmnEdge,
    ) = BpmnDefinitionContext(
        BpmnDefinition(
            processId = "P",
            processName = "P",
            nodes =
            listOf(
                BpmnStartEvent(id = "s"),
                BpmnExclusiveGateway(id = "gw", name = "Q?"),
                BpmnUserTask(id = "a", name = "A"),
                BpmnUserTask(id = "b", name = "B"),
                BpmnEndEvent(id = "e"),
            ),
            sequences =
            listOf(
                BpmnEdge(id = "f1", sourceRef = "s", targetRef = "gw"),
                edgeFa,
                edgeFb,
                BpmnEdge(id = "fae", sourceRef = "a", targetRef = "e"),
                BpmnEdge(id = "fbe", sourceRef = "b", targetRef = "e"),
            ),
        ),
    )
}
