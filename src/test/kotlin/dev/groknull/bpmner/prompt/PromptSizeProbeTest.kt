/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import dev.groknull.bpmner.repair.RepairTestFixtures
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.fail

/**
 * Ratchet test for the size of LLM-facing prompts (and full prompt + schema payloads).
 *
 * Reads baselines from `src/test/resources/prompt-baselines.json` via [PromptBaselineRatchet].
 * The ratchet derives ceiling as `chars × 1.15`; growth past ceiling fails, shrinkage below
 * baseline also fails (pointing the dev at `bazel run //src/test:update_prompt_baselines`).
 *
 * Each probe surfaces as a `DynamicTest` named `"<key> stays within budget"`, so a failing
 * probe still reports a self-describing name in the test output without a dedicated `@Test`
 * method per call-site.
 *
 * Canonical inputs live in [PromptFixtures] and [RepairTestFixtures] so the update binary and
 * the behaviour test can reuse them. Uses [RepairTestFixtures] (the `repair` module's published
 * root test fixture) instead of reaching directly into `repair.internal.adapter.outbound`
 * (S5 — ARCHITECTURE §5 S5, §1.5).
 */
class PromptSizeProbeTest {
    private val ratchet = PromptBaselineRatchet.fromClasspath()

    @TestFactory
    fun `probe stays within budget`(): List<DynamicTest> {
        fun probe(key: String, measure: () -> Int): DynamicTest = DynamicTest.dynamicTest("$key stays within budget") {
            ratchet.check(key, measure())?.let { fail(it) }
        }
        return listOf(
            probe("contractPrompt") { PromptFixtures.contract.render().length },
            probe("contractFullPayload") { PromptFixtures.contract.fullPayload().length },
            probe("generationPrompt") { PromptFixtures.generation.render().length },
            probe("generationFullPayload") { PromptFixtures.generation.fullPayload().length },
            probe("alignmentPrompt") { PromptFixtures.alignment.render().length },
            probe("alignmentFullPayload") { PromptFixtures.alignment.fullPayload().length },
            probe("readinessPrompt") { PromptFixtures.readiness.render().length },
            probe("readinessFullPayload") { PromptFixtures.readiness.fullPayload().length },
            probe("repairPatchPrompt") { RepairTestFixtures.renderPatchFeedback().length },
            probe("repairFullPrompt") { RepairTestFixtures.renderFullFeedback().length },
        )
    }
}
