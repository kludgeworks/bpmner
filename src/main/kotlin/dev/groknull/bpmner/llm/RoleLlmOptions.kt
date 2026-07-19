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
 *
 * **Native structured output (contract item 1) is delivered here by omission, not by an
 * extra call.** No role built on this seam invokes `.withNativeStructuredOutput(...)`, so
 * every role's [LlmOptions.getNativeStructuredOutput] stays `null` →
 * `NativeStructuredOutputMode.DEFAULT`. Embabel's own `promptRunner.creating(...)`/
 * `createObject(...)` path already negotiates `DEFAULT` as "native output when the
 * provider capability is supported and the target type's schema is structurally
 * compatible, otherwise fall back to prompt-schema+validation" — with zero bpmner code.
 * Adding an explicit `.withNativeStructuredOutput(NativeStructuredOutputMode.DEFAULT)`
 * call would be a no-op duplicating this implicit default.
 * `.withNativeStructuredOutput(NativeStructuredOutputMode.DISABLED)` remains the documented
 * escape hatch on a specific role's [LlmOptions] if a future schema change ever needs to
 * force the fallback path explicitly; it is not exercised today (YAGNI until a real need
 * appears).
 */
fun defaultRoleLlmOptions(role: String): LlmOptions =
    LlmOptions.withLlmForRole(role).withAnthropicCaching(systemPrompt = true, tools = true)
