/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import org.springframework.modulith.ApplicationModule

/**
 * LLM provider-wiring infra module — registers @Profile-gated OpenAI-compatible model providers
 * (DeepSeek / GitHub Models / OpenRouter). Cross-cutting infrastructure: depends on no kernel or
 * capability module.
 */
@ApplicationModule
internal object LlmModule
