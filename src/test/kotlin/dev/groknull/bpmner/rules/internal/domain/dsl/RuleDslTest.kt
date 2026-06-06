/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.dsl

import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.nlp.PosTag
import dev.groknull.bpmner.rules.internal.domain.primitives.PresenceCheckConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the rule-id derivation (`id = "<shortCode>-<slug>"`) — the #378 conversion's highest-risk
 * landmine — and the three DSL builders. The expected shortCodes are pinned against
 * `linter/pkl/schema/RuleCategory.pkl`; a drift here would silently rename rule ids.
 */
class RuleDslTest {
    @Test
    fun `shortCodes match the Pkl schema exactly`() {
        val expected = mapOf(
            RuleCategory.ACTIVITY to "act",
            RuleCategory.ASSOCIATION to "assoc",
            RuleCategory.ARTIFACT to "art",
            RuleCategory.DATA to "data",
            RuleCategory.EVENT to "evt",
            RuleCategory.FLOW to "flow",
            RuleCategory.GATEWAY to "gtw",
            RuleCategory.GENERAL to "gen",
            RuleCategory.LANE to "lane",
            RuleCategory.MESSAGE to "msg",
            RuleCategory.NAME to "name",
            RuleCategory.POOL to "pool",
            RuleCategory.DEFINITION to "def",
        )
        // Every category is pinned (catches a new category added without a shortCode assertion).
        assertEquals(RuleCategory.entries.size, expected.size)
        expected.forEach { (category, shortCode) -> assertEquals(shortCode, category.shortCode) }
    }

    @Test
    fun `id is shortCode plus slug`() {
        assertEquals("act-verb-object-name", ruleId(RuleCategory.ACTIVITY, "Verb Object Name"))
        assertEquals("name-uncommon-abbreviations", ruleId(RuleCategory.NAME, "Uncommon Abbreviations"))
        assertEquals("gtw-superfluous-gateway", ruleId(RuleCategory.GATEWAY, "Superfluous Gateway"))
    }

    @Test
    fun `slug lowercases and collapses non-alphanumeric runs without trimming`() {
        assertEquals("verb-object-name", ruleSlug("Verb Object Name"))
        // Mirrors Pkl replaceAll("[^a-z0-9]+", "-") exactly: no leading/trailing trim.
        assertEquals("don-t-repeat-", ruleSlug("Don't Repeat!"))
        assertEquals("a-b-c", ruleSlug("A / B / C"))
    }

    @Test
    fun `fromLabel round-trips every category`() {
        RuleCategory.entries.forEach { category ->
            assertSame(category, RuleCategory.fromLabel(category.label))
        }
    }

    @Test
    fun `primitiveRule derives id and carries metadata`() {
        val rule = primitiveRule(
            name = "Verb Object Name",
            category = RuleCategory.ACTIVITY,
            config = PresenceCheckConfig,
            intent = "intent",
            forModellers = "for modellers",
            forAI = "for ai",
            targetElements = listOf("bpmn:Task"),
            errorMessages = mapOf("default" to "msg"),
            nlp = NoopNlp,
        )
        assertEquals("act-verb-object-name", rule.id)
        assertEquals("act-verb-object-name", rule.metadata.id)
        assertEquals(RuleCategory.ACTIVITY, rule.metadata.category)
        assertEquals(RuleSeverity.WARNING, rule.metadata.severity)
    }

    @Test
    fun `compositeRule builds sub-checks under derived id`() {
        val rule = compositeRule(
            name = "Boundary Event Constraints",
            category = RuleCategory.EVENT,
            intent = "intent",
            forModellers = "for modellers",
            forAI = "for ai",
            targetElements = listOf("bpmn:BoundaryEvent"),
            errorMessages = mapOf("detached" to "msg"),
            nlp = NoopNlp,
        ) {
            sub("detached", PresenceCheckConfig)
        }
        assertEquals("evt-boundary-event-constraints", rule.id)
    }

    @Test
    fun `compositeBuilder collects sub-checks in order`() {
        val config = CompositeBuilder(listOf("bpmn:Task"))
            .apply {
                sub("a", PresenceCheckConfig)
                sub("b", PresenceCheckConfig)
            }
            .build()
        assertEquals(listOf("a", "b"), config.subChecks.map { it.diagnosticCode })
        assertEquals(listOf("bpmn:Task"), config.targetTypes)
    }

    @Test
    fun `llmRule returns a spec with derived id and INFO severity`() {
        val spec = llmRule(
            name = "Business Clarity Over Technical Detail",
            category = RuleCategory.GENERAL,
            prompt = "Review labels for business readability.",
            intent = "intent",
            forModellers = "for modellers",
            forAI = "for ai",
            targetElements = listOf("bpmn:Process"),
            errorMessages = mapOf("default" to "msg"),
        )
        assertEquals("gen-business-clarity-over-technical-detail", spec.metadata.id)
        assertEquals(RuleSeverity.INFO, spec.metadata.severity)
        assertEquals("Review labels for business readability.", spec.config.prompt)
        assertTrue(spec.config.rubric == null)
    }

    private object NoopNlp : BpmnNlp {
        override fun tokens(text: String): List<String> = emptyList()
        override fun posTags(text: String): List<PosTag> = emptyList()
        override fun lemma(token: String): String = token.lowercase()
        override fun lemmasOf(text: String): List<String> = emptyList()
    }
}
