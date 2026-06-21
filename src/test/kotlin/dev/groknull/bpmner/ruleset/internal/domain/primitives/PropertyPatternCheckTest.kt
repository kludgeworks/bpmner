/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength")

package dev.groknull.bpmner.ruleset.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Primitive-level coverage for [PropertyPatternCheck]. Mirrors the per-primitive split already
 * used by [CompositeCheckTest] and [NlpPrimitivesTest] — each class owns its private `context` /
 * `metadata` helpers.
 *
 * For end-to-end coverage of specific rules that consume this primitive (with real Pkl wiring)
 * see the per-rule classes under `domain/pkl/`.
 */
internal class PropertyPatternCheckTest {
    @Test
    fun `property pattern skips blank values and flags non-matching values`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("bad", "approve request"), BpmnEndEvent("e")))
        val check = PropertyPatternCheck()

        assertEquals(
            listOf("bad"),
            check.evaluate(
                ctx,
                metadata("pattern", "bpmn:UserTask"),
                PropertyPatternCheckConfig("name", "^[A-Z].*", "sentence case"),
            ).map { it.elementId },
        )
        assertTrue(check.evaluate(ctx, metadata("blank", "bpmn:EndEvent"), PropertyPatternCheckConfig("name", "^[A-Z].*")).isEmpty())
    }

    @Test
    fun `forbiddenVocabulary flags words case-insensitively independent of regex`() {
        val ctx = context(
            nodes = listOf(
                BpmnUserTask("ok", "Process invoice"),
                BpmnUserTask("hit-direct", "Submit api request"),
                BpmnUserTask("hit-case", "SUBMIT API REQUEST"),
                BpmnUserTask("substring-only", "Submit apiary"),
            ),
        )
        val check = PropertyPatternCheck()
        // Permissive regex (".+") isolates the forbidden-vocab behaviour from regex flagging.
        val config = PropertyPatternCheckConfig(
            property = "name",
            pattern = ".+",
            forbiddenVocabulary = listOf("api", "svc"),
        )

        val flagged = check.evaluate(ctx, metadata("forbidden", "bpmn:UserTask"), config).map { it.elementId }.toSet()

        assertTrue("hit-direct" in flagged, "lowercase 'api' word should flag")
        assertTrue("hit-case" in flagged, "uppercase 'API' word should flag (case-insensitive)")
        assertTrue("ok" !in flagged, "no forbidden word — pass")
        assertTrue("substring-only" !in flagged, "'apiary' contains 'api' as a substring but not a whole word — pass")
    }

    @Test
    fun `allowedVocabulary scrubs allowed words before regex evaluation`() {
        val ctx = context(
            nodes = listOf(
                BpmnUserTask("plain", "Submit form"),
                BpmnUserTask("allowed-only", "Submit BPMN form"),
                BpmnUserTask("disallowed-acronym", "Submit XYZ form"),
                BpmnUserTask("everything-allowed", "BPMN ACME"),
            ),
        )
        val check = PropertyPatternCheck()
        // Reject any all-caps run of length >= 2; allowedVocabulary exempts specific acronyms.
        val config = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^(?!.*\\b[A-Z]{2,}\\b).+$",
            patternDescription = "no uncommon uppercase abbreviations",
            allowedVocabulary = listOf("BPMN", "ACME"),
        )

        val flagged = check.evaluate(ctx, metadata("allowed", "bpmn:UserTask"), config).map { it.elementId }.toSet()

        assertTrue("plain" !in flagged, "no all-caps — pass")
        assertTrue("allowed-only" !in flagged, "BPMN exempted — pass")
        assertTrue("disallowed-acronym" in flagged, "XYZ not in allowlist — flag")
        assertTrue("everything-allowed" !in flagged, "scrubbed value is empty after removing BPMN + ACME — pass")
    }

    @Test
    fun `allowedVocabulary does not mask forbiddenVocabulary hits`() {
        // The two lists carry orthogonal intent — forbiddenVocabulary is absolute and runs
        // against the unscrubbed value. An allowed-list entry shadowing a forbidden word would
        // be a bug.
        val ctx = context(nodes = listOf(BpmnUserTask("t", "Submit api BPMN")))
        val check = PropertyPatternCheck()
        val config = PropertyPatternCheckConfig(
            property = "name",
            pattern = ".+",
            forbiddenVocabulary = listOf("api"),
            allowedVocabulary = listOf("BPMN", "api"),
        )

        assertEquals(
            listOf("t"),
            check.evaluate(ctx, metadata("orthogonal", "bpmn:UserTask"), config).map { it.elementId },
        )
    }

    @Test
    fun `forbidden-only hit names the matched token in the diagnostic message`() {
        // PR #284 (#281) review R2: when only the forbiddenVocabulary path fires, the diagnostic
        // must say WHICH token to remove rather than echo patternDescription (which describes
        // the regex's intent and is misleading on a pure forbidden-vocab hit).
        val ctx = context(nodes = listOf(BpmnUserTask("t", "Submit api request")))
        val check = PropertyPatternCheck()
        val config = PropertyPatternCheckConfig(
            property = "name",
            pattern = ".+",
            patternDescription = "labels should be business-readable",
            forbiddenVocabulary = listOf("api"),
        )

        val message = check.evaluate(ctx, metadata("forbidden-msg", "bpmn:UserTask"), config).single().message
        assertTrue(
            message.contains("contains forbidden token 'api'"),
            "forbidden-only hit should quote the matched token: $message",
        )
        assertTrue(
            !message.contains("business-readable"),
            "forbidden-only hit should NOT echo patternDescription: $message",
        )
    }

    @Test
    fun `combined regex-miss and forbidden hit reports both reasons`() {
        // Belt-and-braces: when both checks fire we expect the patternDescription AND the matched
        // forbidden token in the message — the rule author's regex intent plus the actionable
        // token reference. Fixture: `api` is a standalone word (forbidden hits) AND `callForm`
        // has a mid-token lowercase→uppercase transition (camelCase pattern fails).
        val ctx = context(nodes = listOf(BpmnUserTask("t", "api callForm")))
        val check = PropertyPatternCheck()
        val config = PropertyPatternCheckConfig(
            property = "name",
            pattern = "^(?!.*[a-z][A-Z]).+$",
            patternDescription = "no camelCase",
            forbiddenVocabulary = listOf("api"),
        )

        val message = check.evaluate(ctx, metadata("combined", "bpmn:UserTask"), config).single().message
        assertTrue(message.contains("no camelCase"), "combined message should keep patternDescription: $message")
        assertTrue(message.contains("contains forbidden token 'api'"), "combined message should also name the token: $message")
    }
}
