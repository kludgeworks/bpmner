/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Smoke test for the [BpmnRule] extension-point contract. Verifies that a trivial inline
 * implementation:
 *
 * - Compiles against the published interface signature (guards against future drift in
 *   `id` or `evaluate(ctx)`).
 * - Accepts a real [BpmnDefinitionContext] built from a `core.BpmnDefinition` instance —
 *   exercising the api ↔ core IS-A relationship landed in PR-2 (#227).
 * - Carries the rule's `id` through to every emitted [RuleDiagnostic.ruleId].
 *
 * Semantic correctness of any specific rule is the consumer's concern (#213); this test
 * only pins the interface shape.
 */
class BpmnRuleContractTest {
    /** A minimal `BpmnRule` impl that flags every node lacking a name. */
    private class RequireNamedNodes : BpmnRule {
        override val id: String = "test-require-named-nodes"
        override val metadata: RuleMetadata = testMetadata(id)

        override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.nodes
            .filter { it.name.isNullOrBlank() }
            .map { node ->
                RuleDiagnostic(
                    diagnosticCode = "test-missing-name",
                    ruleId = id,
                    severity = RuleSeverity.WARNING,
                    message = "Node ${node.id} requires a name",
                    elementId = node.id,
                )
            }
    }

    private companion object {
        fun testMetadata(
            id: String,
            errorMessages: Map<String, String> = mapOf("test-missing-name" to "Node requires a name."),
        ): RuleMetadata = RuleMetadata(
            id = id,
            name = "Test Require Named Nodes",
            slug = "test-require-named-nodes",
            category = RuleCategory.GENERAL,
            intent = "Exercise the BpmnRule contract in tests.",
            forModellers = "Test metadata for modellers.",
            forAI = "Test metadata for AI.",
            targetElements = listOf("bpmn:FlowNode"),
            errorMessages = errorMessages,
        )
    }

    @Test
    fun `BpmnRule impl can evaluate a BpmnDefinitionContext and emit typed diagnostics`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P1",
                    processName = "Test process",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "start", name = "Started"),
                        BpmnUserTask(id = "task", name = null), // missing name on purpose
                        BpmnEndEvent(id = "end", name = "Done"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "start", targetRef = "task"),
                        BpmnEdge(id = "f2", sourceRef = "task", targetRef = "end"),
                    ),
                ),
            )

        val rule: BpmnRule = RequireNamedNodes()
        val diagnostics = rule.evaluate(ctx)

        assertEquals(rule.id, rule.metadata.id)
        assertEquals(1, diagnostics.size, "Expected exactly the unnamed user task to be flagged")
        val only = diagnostics.single()
        assertEquals("test-require-named-nodes", only.ruleId)
        assertEquals("test-missing-name", only.diagnosticCode)
        assertEquals("task", only.elementId)
        assertEquals(RuleSeverity.WARNING, only.severity)
    }

    @Test
    fun `BpmnRule id is the rule identifier surfaced on every diagnostic emitted`() {
        val ctx =
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P2",
                    processName = "Four anonymous nodes",
                    nodes =
                    listOf(
                        BpmnStartEvent(id = "s"),
                        BpmnUserTask(id = "t1"),
                        BpmnUserTask(id = "t2"),
                        BpmnEndEvent(id = "e"),
                    ),
                    sequences =
                    listOf(
                        BpmnEdge(id = "f1", sourceRef = "s", targetRef = "t1"),
                        BpmnEdge(id = "f2", sourceRef = "t1", targetRef = "t2"),
                        BpmnEdge(id = "f3", sourceRef = "t2", targetRef = "e"),
                    ),
                ),
            )

        val rule = RequireNamedNodes()
        val diagnostics = rule.evaluate(ctx)

        assertEquals(4, diagnostics.size, "Expected all 4 anonymous nodes to be flagged")
        assertTrue(
            diagnostics.all { it.ruleId == rule.id },
            "Every diagnostic must carry the rule's `id` as `ruleId`",
        )
    }

    @Test
    fun `RuleMetadata rejects empty error messages at construction time`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                testMetadata("test-empty-errors", errorMessages = emptyMap())
            }

        assertEquals("Rule 'test-empty-errors' must define at least one error message", exception.message)
    }
}
