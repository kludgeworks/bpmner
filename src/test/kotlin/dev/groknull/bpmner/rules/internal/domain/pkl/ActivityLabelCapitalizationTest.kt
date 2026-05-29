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
 * Per-rule test for `act-activity-label-capitalization`. Loads the production rule from the
 * Pkl catalog (no synthetic config) and locks the current behaviour: the rule flags activity
 * labels whose first character is not uppercase.
 */
internal class ActivityLabelCapitalizationTest {
    private val rule = loadRule("act-activity-label-capitalization")

    @Test
    fun `sentence-case label passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit form"))))
    }

    @Test
    fun `single capitalised word passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Approve"))))
    }

    @Test
    fun `lowercase first character flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "submit form"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `label leading with digit flags`() {
        // The pattern is `^[A-Z].*` — `1st draft` doesn't start with a capital letter.
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "1st draft"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `mid-string capitalisation passes - rule is anchored at start only`() {
        // `Send PO` has an internal capital run but the rule only checks the leading character.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Send PO"))))
    }
}
