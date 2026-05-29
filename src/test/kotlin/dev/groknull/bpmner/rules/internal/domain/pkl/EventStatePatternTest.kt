/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertFires
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertSilent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.context
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import org.junit.jupiter.api.Test

/**
 * Per-rule test for `evt-event-state-pattern`. The rule routes to `GrammaticalShapeCheck`
 * with `mode = STATE_LABEL`, so it passes on labels that lead with a noun, past-participle
 * verb, or adjective, and fires on labels that lead with an active verb form. The cases
 * below cover both halves of that split with single-fault inputs.
 */
internal class EventStatePatternTest {
    private val rule = loadRule("evt-event-state-pattern")

    @Test
    fun `noun plus past participle passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", "Order received"))))
    }

    @Test
    fun `noun plus past tense verb passes`() {
        // `failed` is past tense / past participle of an irregular-but-dictionary-mapped verb;
        // STATE_LABEL accepts it the same way it accepts `received`.
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", "Payment failed"))))
    }

    @Test
    fun `bare state token passes`() {
        // Single adjective / past-participle lead is enough for STATE_LABEL — no noun required.
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", "Approved"))))
    }

    @Test
    fun `leading active verb flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnStartEvent("e", "Process the order"))),
            expectedElementIds = listOf("e"),
        )
    }

    @Test
    fun `leading send verb flags`() {
        // `Send invoice` reads as an action — exactly what an event label should not be.
        assertFires(
            rule,
            context(nodes = listOf(BpmnStartEvent("e", "Send invoice"))),
            expectedElementIds = listOf("e"),
        )
    }

    @Test
    fun `blank or empty name is silent`() {
        // GrammaticalShapeCheck.evaluate skips blank values via isNullOrBlank.
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", ""))))
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", "   "))))
    }

    @Test
    fun `pure punctuation is silent`() {
        // matchesShape short-circuits to true when the tokenizer returns no tokens.
        assertSilent(rule, context(nodes = listOf(BpmnStartEvent("e", "!!!"))))
    }
}
