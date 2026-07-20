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
 *
 * **Native-enforcement assessment (epic #592 stage 2, goal 6):** all five roles bound to this
 * seam — readiness, alignment, contract-extractor, generator, and repair's four sub-roles
 * (repair-label, repair-patch, repair-rewrite, repairer) — stay on `DEFAULT` for every provider
 * (OpenAI, Anthropic, Gemini, Mistral, and the OpenAI-compatible registrations); none forces
 * `NATIVE`. No role's schema or observed failure history argues for forcing `NATIVE` (which would
 * remove the fallback safety net `DEFAULT` gives for free) or `DISABLED` (which defeats the point
 * of the opt-in). `BpmnConfigBindingTest` statically guards that no role's `LlmOptions` has
 * diverged from this.
 */
fun defaultRoleLlmOptions(role: String): LlmOptions =
    LlmOptions.withLlmForRole(role).withAnthropicCaching(systemPrompt = true, tools = true)
