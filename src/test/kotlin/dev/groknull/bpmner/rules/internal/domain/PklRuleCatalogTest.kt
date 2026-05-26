/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
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
    fun `round-trip - ActivityLabelCapitalization fires from the Pkl path on a lowercase activity label`() {
        // The first ported rule (#241 2D.7 phase 1). This test proves the entire chain works
        // end-to-end through PklRuleCatalog:
        //   RulesIndex.pkl -> ConfigEvaluator -> codegen'd POJO -> BpmnRuleAdapter ->
        //   CheckConfigMapper -> PropertyPatternCheck -> RuleDiagnostic.
        // The rule is configured with pattern "^[A-Z].*"; a lowercase-starting activity name
        // is the canonical violation kind.
        val catalog = PklRuleCatalog(emptyList())
        val rule = catalog.ruleById(ACTIVITY_LABEL_RULE_ID)
            ?: error("Pkl rule '$ACTIVITY_LABEL_RULE_ID' not loaded; check the rule's checkPrimitive/checkConfig in RulesIndex")

        val ctx = ctxWithUserTask(taskId = "t-bad", taskName = "approve request")
        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size, "expected exactly one diagnostic for the lowercase label")
        val diag = diagnostics.single()
        assertEquals(ACTIVITY_LABEL_RULE_ID, diag.ruleId)
        assertEquals("t-bad", diag.elementId)
        assertTrue(diag.message.contains("Activity label"), "expected default error message; got: ${diag.message}")
    }

    @Test
    fun `round-trip - ActivityLabelCapitalization is silent on a sentence-case label`() {
        val catalog = PklRuleCatalog(emptyList())
        val rule = catalog.ruleById(ACTIVITY_LABEL_RULE_ID)
            ?: error("Pkl rule '$ACTIVITY_LABEL_RULE_ID' not loaded")

        val ctx = ctxWithUserTask(taskId = "t-good", taskName = "Approve request")
        assertEquals(emptyList<RuleDiagnostic>(), rule.evaluate(ctx))
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

    private fun ctxWithUserTask(taskId: String, taskName: String): BpmnDefinitionContext = BpmnDefinitionContext(
        BpmnDefinition(
            processId = "P",
            processName = "Process",
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask(taskId, taskName),
                BpmnEndEvent("e", "End"),
            ),
            sequences = listOf(
                BpmnEdge("f1", "s", taskId),
                BpmnEdge("f2", taskId, "e"),
            ),
        ),
    )

    companion object {
        private const val ACTIVITY_LABEL_RULE_ID = "act-activity-label-capitalization"
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
