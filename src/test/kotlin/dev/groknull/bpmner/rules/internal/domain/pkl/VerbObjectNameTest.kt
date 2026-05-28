/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertFires
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertSilent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.context
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import org.junit.jupiter.api.Test

/**
 * Per-rule test for `act-verb-object-name`. The rule is a CompositeCheck binding two
 * sub-checks — `tooShort` (PropertyPatternCheck — at least two whitespace-separated tokens)
 * and `missingVerb` (PartOfSpeechCheck — leading token POS = VERB). This test class exercises
 * both branches.
 */
internal class VerbObjectNameTest {
    private val rule = loadRule("act-verb-object-name")

    @Test
    fun `verb plus object label passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit form"))))
    }

    @Test
    fun `single-word label fires tooShort`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit"))),
            expectedElementIds = listOf("t"),
            expectedDiagnosticCode = "tooShort",
        )
    }

    @Test
    fun `non-verb leading word fires missingVerb`() {
        // `Customer order` is two words, so `tooShort` is silent. `Customer` is not
        // classified as a verb by the NLP, so `missingVerb` fires.
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Customer order"))),
            expectedElementIds = listOf("t"),
            expectedDiagnosticCode = "missingVerb",
        )
    }
}
