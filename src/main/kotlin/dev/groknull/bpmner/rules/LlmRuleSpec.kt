/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.RuleMetadata

/**
 * One LLM rule's evaluation unit: the rule's full [metadata] (carrying `id`, `forAI`,
 * `errorMessages`, etc.) paired with its typed [config]. The Phase 2D loader assembles a
 * `List<LlmRuleSpec>` from `.pkl` rules whose `checkPrimitive == "LlmCheckRule"` and hands
 * it to [LlmRuleEvaluationRequest], which the LLM rule agent then evaluates in batches.
 */
data class LlmRuleSpec(
    val metadata: RuleMetadata,
    val config: LlmCheckRuleConfig,
)
