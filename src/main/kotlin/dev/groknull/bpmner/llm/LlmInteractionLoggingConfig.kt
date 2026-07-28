/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Opt-in diagnostic logging of user workflow text and complete LLM exchanges.
 *
 * Message content may be sensitive, so this remains disabled unless explicitly enabled through
 * the verbose profile or `BPMNER_LOGGING_LLM_INTERACTIONS=true`.
 */
@ConfigurationProperties("bpmner.logging")
data class LlmInteractionLoggingConfig(
    val llmInteractions: Boolean = false,
)
