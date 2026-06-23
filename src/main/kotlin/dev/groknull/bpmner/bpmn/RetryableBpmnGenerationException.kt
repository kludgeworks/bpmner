/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import com.embabel.agent.core.Retryable

/**
 * Marks an LLM-output BPMN failure as retryable so the Embabel planner re-prompts with the
 * violation as feedback. The repair loop is the second safety net per ARCHITECTURE §1.
 *
 * Implementing [Retryable] ensures the Embabel retry policy classifies this exception as
 * retryable on the cause chain, allowing the outline-retry path to recover from LLM-output
 * validation failures during rendering and topology checks (ADR-001, ADR-003).
 *
 * @see com.embabel.agent.core.Retryable
 */
class RetryableBpmnGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    Retryable
