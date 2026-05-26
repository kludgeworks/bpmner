/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.BpmnDefinition

/**
 * Blackboard input to the LLM rule agent's `@AchievesGoal evaluateLlmRules` action.
 *
 * Wraps a BPMN definition with the list of LLM rules to evaluate. Consumers (the
 * validation pipeline, a shell command, an HTTP entrypoint, …) construct one of these and
 * dispatch it to the agent via Embabel's goal-routing — i.e. by handing it to a
 * `PromptRunner`/process whose `startingInputTypes` includes this type.
 */
data class LlmRuleEvaluationRequest(
    val definition: BpmnDefinition,
    val rules: List<LlmRuleSpec>,
)
