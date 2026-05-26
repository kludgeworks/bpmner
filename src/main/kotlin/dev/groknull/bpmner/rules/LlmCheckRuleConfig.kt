/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * Typed Kotlin shape of the `LlmCheckRule` Pkl config class.
 *
 * Public because it's part of [LlmRuleSpec], which itself is exposed on the public
 * [LlmRuleEvaluationRequest] passed to [dev.groknull.bpmner.rules.internal.adapter.inbound.LlmRuleAgent].
 *
 * - [prompt] — the rule-specific question the LLM is asked, drawn from the rule's Pkl
 *   `forAI` field. The agent assembles this with the BPMN context and other batched rules'
 *   prompts into a single LLM round-trip.
 * - [rubric] — optional grading guidance appended to the prompt when present. Lets rule
 *   authors give the model an explicit "fail vs. pass" calibration without padding every
 *   `forAI` string with grading clauses.
 */
data class LlmCheckRuleConfig(
    val prompt: String,
    val rubric: String? = null,
)
