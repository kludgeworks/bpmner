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
 * per role config. Chain further per-call options (e.g. `.withMaxTokens(n)`) on the
 * result — [LlmOptions] is an immutable copy-with builder.
 *
 * No role built on this seam calls `.withNativeStructuredOutput(...)`, so every role
 * gets Embabel's implicit `NativeStructuredOutputMode.DEFAULT` (native output when the
 * provider and schema support it, otherwise fallback) with zero bpmner code.
 * `.withNativeStructuredOutput(NativeStructuredOutputMode.DISABLED)` remains available
 * on a specific role's [LlmOptions] as an escape hatch if a future schema change needs
 * to force the fallback path explicitly.
 */
fun defaultRoleLlmOptions(role: String): LlmOptions =
    LlmOptions.withLlmForRole(role).withAnthropicCaching(systemPrompt = true, tools = true)
