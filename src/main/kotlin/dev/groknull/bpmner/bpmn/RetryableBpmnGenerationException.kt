/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import com.embabel.agent.core.Retryable

/**
 * Exception thrown when LLM-generated BPMN output fails validation during rendering or topology
 * checks. Implementing [Retryable] ensures the Embabel retry policy classifies this as retryable
 * so the planner re-prompts with the violation as feedback.
 *
 * This exception replaces `error(...)` hard aborts on LLM-output-triggered failures, enabling the
 * outline-retry path and repair loop to recover instead of aborting the entire pipeline.
 *
 * @see com.embabel.agent.core.Retryable
 */
class RetryableBpmnGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    Retryable
