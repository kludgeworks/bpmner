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
 * Per-rule test for `name-business-meaningful-label`. The rule combines a regex (rejecting
 * underscores, slashes, camelCase, and digits) with a case-insensitive forbidden-vocabulary
 * blocklist (`technicalTokens` from the rule's own `staticConfig`). Every case below is a
 * single-fault input so each detection category is locked independently.
 */
internal class BusinessMeaningfulLabelTest {
    private val rule = loadRule("name-business-meaningful-label")

    @Test
    fun `plain business-readable label passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit form"))))
    }

    @Test
    fun `acronym in otherwise readable label passes`() {
        // Uppercase abbreviations are out of this rule's scope — UncommonAbbreviations handles
        // them. The pattern only flags lowercase-to-uppercase transitions, not all-caps runs.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit BPMN form"))))
    }

    @Test
    fun `underscore flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit_form"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `slash path flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit/form"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `camelCase flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "submitForm"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `PascalCase flags`() {
        // PascalCase has a lowercase-to-uppercase transition (`OrderForm` → `r→F`).
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "OrderForm"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `digit in label flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Form1"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `forbidden technical token flags`() {
        // `api` is in `staticConfig.technicalTokens` and the regex doesn't reject the
        // surrounding label — this case is what `forbiddenVocabulary` exists for.
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit api request"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `forbidden token check is case-insensitive`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit API request"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `forbidden token substring of a real word does not flag`() {
        // `apiary` contains `api` as a substring but `\b` word boundaries protect it.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit apiary form"))))
    }
}
