/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // one @Test per probe — 4 prompt + 4 full-payload + 2 repair + checkBaseline

package dev.groknull.bpmner.prompt

import dev.groknull.bpmner.repair.internal.adapter.outbound.RepairFixtures
import kotlin.test.Test
import kotlin.test.fail

/**
 * Ratchet test for the size of LLM-facing prompts (and full prompt + schema payloads).
 *
 * Reads baselines from `src/test/resources/prompt-baselines.json` via [PromptBaselineRatchet].
 * The ratchet derives ceiling as `chars × 1.15`; growth past ceiling fails, shrinkage below
 * baseline also fails (pointing the dev at `bazel run //src/test:update_prompt_baselines`).
 *
 * Canonical inputs live in [PromptFixtures] and [RepairFixtures] so the update binary and the
 * behaviour test can reuse them.
 */
class PromptSizeProbeTest {
    private val ratchet = PromptBaselineRatchet.fromClasspath()

    @Test
    fun `contract extraction prompt stays within budget`() {
        checkBaseline("contractPrompt", PromptFixtures.renderContractPrompt().length)
    }

    @Test
    fun `bpmn generation prompt stays within budget`() {
        checkBaseline("generationPrompt", PromptFixtures.renderGenerationPrompt().length)
    }

    @Test
    fun `alignment prompt stays within budget`() {
        checkBaseline("alignmentPrompt", PromptFixtures.renderAlignmentPrompt().length)
    }

    @Test
    fun `readiness prompt stays within budget`() {
        checkBaseline("readinessPrompt", PromptFixtures.renderReadinessPrompt().length)
    }

    @Test
    fun `contract extraction full payload stays within budget`() {
        val payload = PromptFixtures.renderContractPrompt() + PromptFixtures.contractSchemaFormat()
        checkBaseline("contractFullPayload", payload.length)
    }

    @Test
    fun `bpmn generation full payload stays within budget`() {
        val payload = PromptFixtures.renderGenerationPrompt() + PromptFixtures.generationSchemaFormat()
        checkBaseline("generationFullPayload", payload.length)
    }

    @Test
    fun `alignment full payload stays within budget`() {
        val payload = PromptFixtures.renderAlignmentPrompt() + PromptFixtures.alignmentSchemaFormat()
        checkBaseline("alignmentFullPayload", payload.length)
    }

    @Test
    fun `readiness full payload stays within budget`() {
        val payload = PromptFixtures.renderReadinessPrompt() + PromptFixtures.readinessSchemaFormat()
        checkBaseline("readinessFullPayload", payload.length)
    }

    @Test
    fun `repair patch feedback prompt stays within budget`() {
        checkBaseline("repairPatchPrompt", RepairFixtures.renderPatchFeedback().length)
    }

    @Test
    fun `repair full feedback prompt stays within budget`() {
        checkBaseline("repairFullPrompt", RepairFixtures.renderFullFeedback().length)
    }

    private fun checkBaseline(key: String, actual: Int) {
        ratchet.check(key, actual)?.let { fail(it) }
    }
}
