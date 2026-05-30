/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the [PromptBaselineRatchet] decision logic directly across the four scenarios that
 * matter for the auto-update flow: in-band, overflow, shrinkage, missing baseline. Keeps the
 * actual ratchet probes in [PromptSizeProbeTest] free of meta-assertions about the ratchet
 * itself.
 */
class PromptSizeProbeBehaviorTest {
    @Test
    fun `in-band measurement returns null`() {
        val ratchet = ratchetWith("foo" to 100)
        val ceiling = (100 * PromptBaselineRatchet.CEILING_MULTIPLIER).toInt()
        // Any value in [baseline, ceiling] should pass silently.
        assertNull(ratchet.check("foo", 100))
        assertNull(ratchet.check("foo", 110))
        assertNull(ratchet.check("foo", ceiling))
    }

    @Test
    fun `growth past ceiling reports overflow with key and actual`() {
        val ratchet = ratchetWith("foo" to 100)
        val ceiling = (100 * PromptBaselineRatchet.CEILING_MULTIPLIER).toInt()
        val message = ratchet.check("foo", ceiling + 1)
        assertTrue(message != null, "expected overflow message")
        assertTrue(message.contains("foo"), "message should name the key: $message")
        assertTrue(message.contains("ceiling"), "message should mention ceiling: $message")
        assertTrue(message.contains((ceiling + 1).toString()), "message should mention the actual value: $message")
    }

    @Test
    fun `shrinkage below baseline reports failure pointing at the updater binary`() {
        val ratchet = ratchetWith("foo" to 100)
        val message = ratchet.check("foo", 90)
        assertTrue(message != null, "expected shrinkage message")
        assertTrue(message.contains("foo"), "message should name the key: $message")
        assertTrue(message.contains("90"), "message should mention the actual value: $message")
        assertTrue(
            message.contains("update_prompt_baselines"),
            "shrinkage message should direct the dev at the updater binary: $message",
        )
    }

    @Test
    fun `missing baseline reports a clear failure`() {
        val ratchet = ratchetWith("foo" to 100)
        val message = ratchet.check("bar", 50)
        assertTrue(message != null, "expected missing-baseline message")
        assertTrue(message.contains("bar"), "message should name the missing key: $message")
        assertTrue(
            message.contains("update_prompt_baselines"),
            "missing-baseline message should direct the dev at the updater binary: $message",
        )
    }

    private fun ratchetWith(vararg entries: Pair<String, Int>): PromptBaselineRatchet {
        val mapper = jacksonObjectMapper()
        val baselines: ObjectNode = mapper.createObjectNode()
        for ((key, chars) in entries) {
            val entry = mapper.createObjectNode().apply {
                put("chars", chars)
                put("updatedBy", "test")
                put("reason", "behaviour-test fixture")
            }
            baselines.set<ObjectNode>(key, entry)
        }
        val root = mapper.createObjectNode().apply {
            put("version", 1)
            set<ObjectNode>("baselines", baselines)
        }
        return PromptBaselineRatchet(root)
    }
}
