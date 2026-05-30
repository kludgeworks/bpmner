/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * ArchUnit-style ratchet for LLM prompt sizes.
 *
 * Each baseline records `chars`; the ceiling is derived in code as `chars × 1.15`. Growth past
 * ceiling fails the build immediately. Shrinkage below the recorded `chars` also fails — with a
 * message pointing the developer at `bazel run //src/test:update_prompt_baselines` to regenerate
 * the file and commit it. Within-band measurements pass silently.
 *
 * The ratchet is read-only; the `update_prompt_baselines` binary is the only writer.
 */
internal class PromptBaselineRatchet(private val baselines: JsonNode) {
    /**
     * Verify [actual] against the recorded baseline for [key].
     * Returns null on success, or a human-readable error message on failure.
     */
    fun check(key: String, actual: Int): String? {
        val node = baselines.path("baselines").path(key)
        if (node.isMissingNode) {
            return "Missing baseline '$key' in prompt-baselines.json. " +
                "Run `bazel run //src/test:update_prompt_baselines` to add it, then commit."
        }
        val baseline = node.path("chars").asInt(0)
        if (baseline <= 0) {
            return "Baseline '$key' has non-positive chars value in prompt-baselines.json. " +
                "Run `bazel run //src/test:update_prompt_baselines` to repair it."
        }
        val ceiling = (baseline * CEILING_MULTIPLIER).toInt()
        return when {
            actual > ceiling ->
                "$key grew past ceiling $ceiling (was $actual). " +
                    "Trim the prompt or raise the baseline."
            actual < baseline ->
                "$key shrank to $actual chars (baseline $baseline). " +
                    "Run `bazel run //src/test:update_prompt_baselines` to lock in the gain, then commit."
            else -> null
        }
    }

    companion object {
        /** Ceiling is derived as `chars × CEILING_MULTIPLIER`; tracks chars by construction. */
        const val CEILING_MULTIPLIER: Double = 1.15

        /** Load the ratchet from the classpath copy of `prompt-baselines.json`. */
        fun fromClasspath(): PromptBaselineRatchet {
            val stream =
                PromptBaselineRatchet::class.java.classLoader.getResourceAsStream("prompt-baselines.json")
                    ?: error("prompt-baselines.json not found on the test classpath")
            return stream.use { PromptBaselineRatchet(jacksonObjectMapper().readTree(it)) }
        }
    }
}
