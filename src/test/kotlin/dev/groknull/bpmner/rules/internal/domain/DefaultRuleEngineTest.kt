/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.rules.RuleRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultRuleEngine]. Exercise the empty-registry happy path, single-rule
 * forwarding, and two-rule aggregation. The engine does not transform or filter
 * diagnostics in Phase 1 — every emitted diagnostic flows through unchanged.
 */
class DefaultRuleEngineTest {
    /** Minimal in-memory [RuleRegistry] for tests — independent of Spring DI. */
    private class TestRegistry(
        private val rules: List<BpmnRule>,
    ) : RuleRegistry {
        override fun activeRules(): List<BpmnRule> = rules

        override fun ruleById(id: String): BpmnRule? = rules.firstOrNull { it.id == id }
    }

    private class NoOpRule(
        override val id: String,
    ) : BpmnRule {
        override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
    }

    private class FlagsEveryNode(
        override val id: String,
    ) : BpmnRule {
        override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ctx.definition.nodes.map { node ->
            RuleDiagnostic(
                diagnosticCode = "test-flag-every-node",
                ruleId = id,
                severity = RuleSeverity.WARNING,
                message = "Flagging ${node.id}",
                elementId = node.id,
            )
        }
    }

    private fun trivialDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "P",
        processName = "Trivial",
        nodes = listOf(BpmnStartEvent(id = "s"), BpmnEndEvent(id = "e")),
        sequences = listOf(BpmnEdge(id = "f1", sourceRef = "s", targetRef = "e")),
    )

    @Test
    fun `empty registry yields a passing evaluation with no diagnostics`() {
        val engine = DefaultRuleEngine(TestRegistry(emptyList()))

        val result = engine.evaluate(trivialDefinition())

        assertTrue(result.diagnostics.isEmpty())
        assertTrue(result.passed, "Empty diagnostics ⇒ passing")
    }

    @Test
    fun `single rule emitting one diagnostic per node flows through untouched`() {
        val engine = DefaultRuleEngine(TestRegistry(listOf(FlagsEveryNode("rule-flagger"))))

        val result = engine.evaluate(trivialDefinition())

        assertEquals(2, result.diagnostics.size, "Two nodes ⇒ two diagnostics")
        assertTrue(result.diagnostics.all { it.ruleId == "rule-flagger" })
        assertTrue(result.passed, "WARNING severity is non-blocking")
    }

    @Test
    fun `multiple rules aggregate diagnostics in registry order`() {
        val flagFirst = FlagsEveryNode("rule-first")
        val flagSecond = FlagsEveryNode("rule-second")
        val noOp = NoOpRule("rule-no-op")

        val engine = DefaultRuleEngine(TestRegistry(listOf(flagFirst, noOp, flagSecond)))

        val result = engine.evaluate(trivialDefinition())

        // Two flagging rules × two nodes = 4 diagnostics. The no-op contributes nothing.
        assertEquals(4, result.diagnostics.size)
        // Order: every diagnostic from `flagFirst` precedes every diagnostic from `flagSecond`.
        val firstRuleIds =
            result.diagnostics
                .take(2)
                .map { it.ruleId }
                .distinct()
        val secondRuleIds =
            result.diagnostics
                .drop(2)
                .map { it.ruleId }
                .distinct()
        assertEquals(listOf("rule-first"), firstRuleIds)
        assertEquals(listOf("rule-second"), secondRuleIds)
    }

    @Test
    fun `rule exception is isolated and surfaced as a rule-execution-failure diagnostic`() {
        val throwingRule =
            object : BpmnRule {
                override val id: String = "rule-throws"

                override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = error("simulated rule crash")
            }
        val survivingRule = FlagsEveryNode("rule-survives")

        val engine = DefaultRuleEngine(TestRegistry(listOf(throwingRule, survivingRule)))

        // No uncaught exception escapes the engine.
        val result = engine.evaluate(trivialDefinition())

        // One synthetic diagnostic for the failure, plus two from the surviving rule (one per node).
        assertEquals(3, result.diagnostics.size)

        val failure = result.diagnostics.first()
        assertEquals("rule-execution-failure", failure.diagnosticCode)
        assertEquals("rule-throws", failure.ruleId)
        assertEquals(RuleSeverity.ERROR, failure.severity)
        assertTrue(
            failure.message.contains("IllegalStateException"),
            "Failure diagnostic message should name the exception type",
        )

        // The surviving rule still emitted its diagnostics — isolation worked.
        val survivingDiagnostics = result.diagnostics.drop(1)
        assertEquals(2, survivingDiagnostics.size)
        assertTrue(survivingDiagnostics.all { it.ruleId == "rule-survives" })
    }
}
