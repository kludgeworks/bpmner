/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertFires
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertSilent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.context
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Per-rule test for `name-uncommon-abbreviations`. The rule rejects any all-caps run of length
 * ≥ 2 except those listed in `staticConfig.commonAcronyms`, which is passed to the runtime via
 * `checkConfig.allowedVocabulary`. The class also pins the rule's repair-metadata round-trip —
 * `LOCAL_MODEL_FIX` + `expandAbbreviations` handler + `replacementMap` — so a future change to
 * the Pkl repair block is caught here.
 *
 * Note: this rule is **opt-in**. `application.yaml` carries a `severity-overrides` entry that
 * disables it by default. The test bypasses the override (it calls `rule.evaluate` directly,
 * not through `DefaultRuleEngine`), so it sees the rule's natural behaviour.
 */
internal class UncommonAbbreviationsTest {
    private val rule = loadRule("name-uncommon-abbreviations")

    @Test
    fun `label with no all-caps runs passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit form"))))
    }

    @Test
    fun `single-letter all-caps does not flag`() {
        // `A` is one letter; the pattern matches runs of >= 2.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Send A note"))))
    }

    @Test
    fun `allowed acronym passes`() {
        // `BPMN` is in `staticConfig.commonAcronyms`.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit BPMN form"))))
    }

    @Test
    fun `multiple allowed acronyms in one label pass`() {
        // After scrubbing the allowed runs the residual label has no all-caps run.
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit BPMN form via API"))))
    }

    @Test
    fun `disallowed acronym flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit XYZ form"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `label containing both allowed and disallowed acronyms flags`() {
        // `BPMN` is exempted; `XYZ` is not — the flag still fires.
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit BPMN via XYZ"))),
            expectedElementIds = listOf("t"),
        )
    }

    @Test
    fun `repair metadata round-trips from Pkl to Kotlin`() {
        val repair = rule.metadata.repair
        assertEquals(RepairKind.LOCAL_MODEL_FIX, repair.kind)
        assertEquals(RepairSafety.SAFE_AUTOMATIC, repair.safety)
        assertEquals("expandAbbreviations", repair.handler)
        // Spot-check a couple of replacementMap entries — the full map is the rule's own
        // documentation; this test pins the load-time shape, not the content.
        val replacementMap = repair.replacementMap ?: error("replacementMap should be populated for this rule")
        assertEquals("request", replacementMap["REQ"])
        assertEquals("response", replacementMap["RESP"])
    }
}
