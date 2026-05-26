/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class PklRuleCatalogTest {

    @Test
    fun `catalog initializes from classpath RulesIndex without throwing`() {
        // Just exercises the Pkl runtime evaluation path against the real RulesIndex.pkl on
        // the test classpath. Until 2D.7 ports rules with checkPrimitive set, every Pkl rule
        // gets skipped at adapt time — but the loader itself must complete cleanly.
        val catalog = PklRuleCatalog(emptyList())
        catalog.activeRules()
        catalog.llmRuleSpecs()
    }

    @Test
    fun `compiled rules are present in activeRules and ruleById`() {
        val compiled = TestBpmnRule("test-compiled-rule")

        val catalog = PklRuleCatalog(listOf(compiled))

        assertEquals(listOf<BpmnRule>(compiled), catalog.activeRules().filter { it === compiled })
        assertSame(compiled, catalog.ruleById("test-compiled-rule"))
    }

    @Test
    fun `ruleById returns null for unknown id`() {
        val catalog = PklRuleCatalog(emptyList())

        assertNull(catalog.ruleById("definitely-not-a-rule-id"))
    }

    @Test
    fun `catalog fails fast when RulesIndex cannot be resolved`() {
        // A bogus modulepath: URI is the closest analogue at unit-test scope of "the .pkl
        // resource is missing from the fat JAR" — Pkl's evaluator throws before any rule is
        // loaded, init {} propagates, Spring would fail bean creation at startup. The fail
        // happens at construction time, before activeRules() is even reachable.
        val failure =
            assertFailsWith<RuntimeException> {
                PklRuleCatalog(
                    compiledRules = emptyList(),
                    rulesIndexUri = "modulepath:/no/such/resource/RulesIndex.pkl",
                )
            }
        val combined = listOfNotNull(failure.message, failure.cause?.message).joinToString(" | ")
        assertTrue(
            combined.contains("no/such/resource") || combined.contains("RulesIndex"),
            "expected error to mention the missing module URI; got: $combined",
        )
    }

    @Test
    fun `catalog reports loaded count clearly when only compiled rules contribute`() {
        // Until 2D.7 ports rules with checkPrimitive set, the Pkl side adds nothing. The
        // catalog must still surface every compiled rule unchanged — this is the property
        // production code paths (RuleEngine, repair handlers) depend on while the new path
        // is incrementally adopted.
        val compiled = listOf(TestBpmnRule("test-r1"), TestBpmnRule("test-r2"))

        val catalog = PklRuleCatalog(compiled)

        val ids = catalog.activeRules().map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("test-r1", "test-r2")))
        assertNotNull(catalog.ruleById("test-r1"))
        assertNotNull(catalog.ruleById("test-r2"))
    }

    private class TestBpmnRule(override val id: String) : BpmnRule {
        override val metadata: RuleMetadata = RuleMetadata(
            id = id,
            name = "Test rule",
            slug = id,
            category = "Test",
            intent = "test",
            forModellers = "test",
            forAI = "test",
            targetElements = listOf("bpmn:Task"),
            errorMessages = mapOf("default" to "violation"),
        )

        override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = emptyList()
    }
}
