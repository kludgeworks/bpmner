/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.embabel.agent.core.NonRetryable

/**
 * Signals that the outline generator's structured-output call failed on Embabel's stable
 * `InvalidLlmReturn*` surface — malformed or invalid model output, distinct from the legitimate
 * structurally-incomplete-node retry path (the framework's own `InvalidLlmReturnFormatException`,
 * manufactured from `toSealed()`'s `IllegalArgumentException` after this call already succeeded).
 * Marked [NonRetryable] because the framework's default retry policy would otherwise keep
 * retrying a doomed raw-format failure alongside the legitimate retry the same action already
 * performs for structural incompleteness.
 */
class BpmnOutlineGenerationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause),
    NonRetryable
