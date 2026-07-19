/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.anthropic.withAnthropicCaching
import com.embabel.common.ai.model.LlmOptions

/**
 * The single seam every bpmner role binds its [LlmOptions] through. Provider-specific
 * tuning — today, Anthropic prompt/tool caching — lives here once instead of duplicated
 * per role config, so a future provider-specific option (or a capability-gated choice
 * between providers) has one call site to extend. Non-Anthropic providers ignore the
 * caching extension harmlessly (Embabel's provider-extension slot is opt-in per
 * provider module). Chain further per-call options (e.g. `.withMaxTokens(n)`) on the
 * result — [LlmOptions] is an immutable copy-with builder.
 */
fun defaultRoleLlmOptions(role: String): LlmOptions =
    LlmOptions.withLlmForRole(role).withAnthropicCaching(systemPrompt = true, tools = true)
