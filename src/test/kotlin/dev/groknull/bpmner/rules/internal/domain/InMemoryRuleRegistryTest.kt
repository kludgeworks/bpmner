/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [InMemoryRuleRegistry]. Cover empty-list initialisation, lookup by id,
 * and duplicate-id handling (last-wins, as documented — the Phase 1H startup check (#216)
 * is the right place to surface the duplication, not this lookup).
 */
class InMemoryRuleRegistryTest {
    private class StubRule(
        override val id: String,
    ) : BpmnRule {
        override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
    }

    @Test
    fun `empty registry returns empty activeRules and null lookups`() {
        val registry = InMemoryRuleRegistry(emptyList())

        assertTrue(registry.activeRules().isEmpty())
        assertNull(registry.ruleById("any"))
    }

    @Test
    fun `populated registry exposes rules in registration order and resolves by id`() {
        val first = StubRule(id = "rule-a")
        val second = StubRule(id = "rule-b")

        val registry = InMemoryRuleRegistry(listOf(first, second))

        assertEquals(listOf(first, second), registry.activeRules())
        assertSame(first, registry.ruleById("rule-a"))
        assertSame(second, registry.ruleById("rule-b"))
        assertNull(registry.ruleById("rule-missing"))
    }

    @Test
    fun `duplicate ids collapse last-wins in the lookup map`() {
        val firstWithId = StubRule(id = "rule-dup")
        val secondWithId = StubRule(id = "rule-dup")

        val registry = InMemoryRuleRegistry(listOf(firstWithId, secondWithId))

        // Both rules remain in the list — the engine still evaluates each.
        assertEquals(2, registry.activeRules().size)
        // The lookup keeps the *last* registered rule with the duplicate id.
        // Detection of the collision is deferred to the #216 startup check.
        assertSame(secondWithId, registry.ruleById("rule-dup"))
    }
}
