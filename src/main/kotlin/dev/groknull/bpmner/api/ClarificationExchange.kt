/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * One Q&A round in the readiness-driven clarification loop, exposed as an annotation-free
 * data contract for the api boundary.
 *
 * The concrete `domain.ClarificationExchange` implementation (in `readiness/`) carries
 * additional optional fields (`relatedMissingAreas`, `relatedDimensions`, `evidence`) that
 * reference readiness-slice types.
 */
interface ClarificationExchange {
    val questionId: String
    val questionText: String
    val answerText: String
}
