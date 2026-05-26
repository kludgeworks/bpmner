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
import kotlin.test.assertNull
import kotlin.test.assertSame

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
