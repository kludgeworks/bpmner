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
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.nlp.testBpmnNlp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Suppress("TooManyFunctions") // round-trip tests fan out per primitive family; one test per rule
internal class PklRuleCatalogTest {
    private val nlp = testBpmnNlp()

    @Test
    fun `catalog initializes from classpath RulesIndex without throwing`() {
        // Just exercises the Pkl runtime evaluation path against the real RulesIndex.pkl on
        // the test classpath. Until 2D.7 ports rules with checkPrimitive set, every Pkl rule
        // gets skipped at adapt time — but the loader itself must complete cleanly.
        val catalog = PklRuleCatalog(emptyList(), nlp)
        catalog.activeRules()
        catalog.llmRuleSpecs()
    }

    @Test
    fun `compiled rules are present in activeRules and ruleById`() {
        val compiled = TestBpmnRule("test-compiled-rule")

        val catalog = PklRuleCatalog(listOf(compiled), nlp)

        assertEquals(listOf<BpmnRule>(compiled), catalog.activeRules().filter { it === compiled })
        assertSame(compiled, catalog.ruleById("test-compiled-rule"))
    }

    @Test
    fun `ruleById returns null for unknown id`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)

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
                    nlp = nlp,
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
        val catalog = PklRuleCatalog(emptyList(), nlp)
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
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById(ACTIVITY_LABEL_RULE_ID)
            ?: error("Pkl rule '$ACTIVITY_LABEL_RULE_ID' not loaded")

        val ctx = ctxWithUserTask(taskId = "t-good", taskName = "Approve request")
        assertEquals(emptyList<RuleDiagnostic>(), rule.evaluate(ctx))
    }

    @Test
    fun `round-trip - DiscouragedBusinessVerbs flags labels starting with a forbidden verb`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("act-discouraged-business-verbs")
            ?: error("Pkl rule 'act-discouraged-business-verbs' not loaded")

        val ctx = ctxWithUserTask(taskId = "t", taskName = "Handle invoice")
        val diagnostics = rule.evaluate(ctx)

        assertEquals(listOf("t"), diagnostics.map { it.elementId })
    }

    @Test
    fun `round-trip - DiscouragedBusinessVerbs is silent when forbidden verb is not leading`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("act-discouraged-business-verbs")
            ?: error("Pkl rule 'act-discouraged-business-verbs' not loaded")

        val ctx = ctxWithUserTask(taskId = "t", taskName = "Review and handle invoice")

        assertEquals(emptyList<RuleDiagnostic>(), rule.evaluate(ctx))
    }

    @Test
    fun `round-trip - StartNoIncoming flags start events with incoming sequence flow`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("evt-start-no-incoming")
            ?: error("Pkl rule 'evt-start-no-incoming' not loaded")

        // Build a context where the start event has an incoming edge — the canonical violation
        // of "start must have zero incoming flow".
        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnUserTask("t1", "First task"),
                    BpmnStartEvent("s", "Start"),
                    BpmnEndEvent("e", "End"),
                ),
                sequences = listOf(
                    BpmnEdge("bad", "t1", "s"),
                    BpmnEdge("ok", "s", "e"),
                ),
            ),
        )

        assertEquals(listOf("s"), rule.evaluate(ctx).map { it.elementId })
    }

    @Test
    fun `round-trip - ConvergingGatewayUnnamed flags named converging gateways`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("gtw-converging-gateway-unnamed")
            ?: error("Pkl rule 'gtw-converging-gateway-unnamed' not loaded")

        // Two start events fanning into one named exclusive gateway, then a single outgoing
        // flow to End — matches the CONVERGING_UNNAMED topology check: incoming >= 2,
        // outgoing <= 1, and name is non-blank.
        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnStartEvent("s1", "Start A"),
                    BpmnStartEvent("s2", "Start B"),
                    BpmnExclusiveGateway("g", "Combine paths"),
                    BpmnEndEvent("e", "End"),
                ),
                sequences = listOf(
                    BpmnEdge("f1", "s1", "g"),
                    BpmnEdge("f2", "s2", "g"),
                    BpmnEdge("f3", "g", "e"),
                ),
            ),
        )

        assertEquals(listOf("g"), rule.evaluate(ctx).map { it.elementId })
    }

    // -------------------------------------------------------------------------------------
    // Phase 3 (#218) NLP-aware rule activations — one round-trip test per activated rule.

    @Test
    fun `round-trip - VerbObjectName fires the missingVerb sub-check on a noun-led activity`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("act-verb-object-name") ?: error("Pkl rule 'act-verb-object-name' not loaded")

        // "Order processing" is two words (passes tooShort) but starts with a noun, not a verb
        // — only the `missingVerb` sub-check should fire.
        val ctx = ctxWithUserTask(taskId = "t", taskName = "Order processing")
        val diagnostics = rule.evaluate(ctx)

        assertEquals(1, diagnostics.size, "expected exactly one diagnostic (missingVerb); got: $diagnostics")
        assertEquals("missingVerb", diagnostics.single().diagnosticCode)
    }

    @Test
    fun `round-trip - EventStateName fires on an action-led start event`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("evt-event-state-name") ?: error("Pkl rule 'evt-event-state-name' not loaded")

        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnStartEvent("bad", "Process the order"),
                    BpmnEndEvent("good", "Order received"),
                ),
                sequences = listOf(BpmnEdge("f1", "bad", "good")),
            ),
        )
        val diagnostics = rule.evaluate(ctx)
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `round-trip - IntermediateEventNotAction fires on an action-led intermediate event`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("evt-intermediate-event-not-action")
            ?: error("Pkl rule 'evt-intermediate-event-not-action' not loaded")

        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnStartEvent("s", "Start"),
                    dev.groknull.bpmner.core.BpmnIntermediateCatchEvent(
                        "bad",
                        name = "Send the notification",
                        eventDefinition = dev.groknull.bpmner.core.BpmnNoneEventDefinition,
                    ),
                    BpmnEndEvent("e", "End"),
                ),
                sequences = listOf(BpmnEdge("f1", "s", "bad"), BpmnEdge("f2", "bad", "e")),
            ),
        )
        val diagnostics = rule.evaluate(ctx)
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `round-trip - GatewayNoWorkLabel fires on a verb-led gateway`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("gtw-gateway-no-work-label") ?: error("Pkl rule 'gtw-gateway-no-work-label' not loaded")

        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnStartEvent("s", "Start"),
                    BpmnExclusiveGateway("bad", "Validate the order"),
                    BpmnEndEvent("e", "End"),
                ),
                sequences = listOf(BpmnEdge("f1", "s", "bad"), BpmnEdge("f2", "bad", "e")),
            ),
        )
        val diagnostics = rule.evaluate(ctx)
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `round-trip - DivergingGatewayQuestion fires on a non-question gateway`() {
        val catalog = PklRuleCatalog(emptyList(), nlp)
        val rule = catalog.ruleById("gtw-diverging-gateway-question")
            ?: error("Pkl rule 'gtw-diverging-gateway-question' not loaded")

        val ctx = BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = listOf(
                    BpmnStartEvent("s", "Start"),
                    BpmnExclusiveGateway("bad", "Order outcome"),
                    BpmnEndEvent("e", "End"),
                ),
                sequences = listOf(BpmnEdge("f1", "s", "bad"), BpmnEdge("f2", "bad", "e")),
            ),
        )
        val diagnostics = rule.evaluate(ctx)
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `catalog reports loaded count clearly when only compiled rules contribute`() {
        // Until 2D.7 ports rules with checkPrimitive set, the Pkl side adds nothing. The
        // catalog must still surface every compiled rule unchanged — this is the property
        // production code paths (RuleEngine, repair handlers) depend on while the new path
        // is incrementally adopted.
        val compiled = listOf(TestBpmnRule("test-r1"), TestBpmnRule("test-r2"))

        val catalog = PklRuleCatalog(compiled, nlp)

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
