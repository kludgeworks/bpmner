/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
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
        assertEquals("edge f-default isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY", diag.message)
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
    fun `multiple default flows on one source emit def-multiple-default-flows`() {
        val ctx =
            BpmnDefinitionContext(
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
                            BpmnEdge(id = "fa", sourceRef = "gw", targetRef = "a", isDefault = true),
                            BpmnEdge(id = "fb", sourceRef = "gw", targetRef = "b", isDefault = true),
                            BpmnEdge(id = "fae", sourceRef = "a", targetRef = "e"),
                            BpmnEdge(id = "fbe", sourceRef = "b", targetRef = "e"),
                        ),
                ),
            )

        val diag = rule.evaluate(ctx).single { it.diagnosticCode == "def-multiple-default-flows" }
        assertEquals(RuleSeverity.ERROR, diag.severity)
        assertEquals("node gw has 2 default flows (fa, fb); at most one is allowed", diag.message)
        assertEquals("gw", diag.elementId)
    }

    @Test
    fun `single default flow on an exclusive gateway emits no diagnostic`() {
        val ctx =
            BpmnDefinitionContext(
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
                            BpmnEdge(id = "fa", sourceRef = "gw", targetRef = "a", isDefault = true),
                            BpmnEdge(id = "fb", sourceRef = "gw", targetRef = "b", conditionExpression = "x"),
                            BpmnEdge(id = "fae", sourceRef = "a", targetRef = "e"),
                            BpmnEdge(id = "fbe", sourceRef = "b", targetRef = "e"),
                        ),
                ),
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
}
