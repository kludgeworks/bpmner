/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * One Q&A round in the readiness-driven clarification loop, exposed as an annotation-free
 * data contract for the api boundary.
 *
 * The concrete `core.ClarificationExchange` implementation carries additional optional
 * fields (`relatedMissingAreas`, `relatedDimensions`, `evidence`) that reference types
 * still living in `core/`. Those fields stay on the core data class until the readiness
 * types also migrate — out of scope for #210.
 */
interface ClarificationExchange {
    val questionId: String
    val questionText: String
    val answerText: String
}
