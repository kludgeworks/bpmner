/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.RuleDiagnostic

/**
 * Blackboard output produced by the LLM rule agent's `@AchievesGoal` action.
 *
 * Each [RuleDiagnostic] carries the originating rule's `id`, the violating element id
 * (when the model identified one), and the LLM-supplied message. The diagnostic's
 * `severity` and `diagnosticCode` come from the originating rule's metadata so downstream
 * consumers can treat LLM-sourced diagnostics identically to deterministic ones.
 */
data class LlmRuleEvaluationResult(
    val diagnostics: List<RuleDiagnostic>,
)
