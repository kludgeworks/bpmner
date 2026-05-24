/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Annotation-free contract for a BPMN generation request. The concrete `core.BpmnRequest`
 * data class implements this interface and also carries the Embabel `PromptContributor`
 * implementation that produces the system prompt — `api/` deliberately knows nothing
 * about Embabel.
 */
interface BpmnRequest {
    val processDescription: String
    val styleGuide: String?
    val outputFile: String?
    val mode: GenerationMode
    val clarificationHistory: List<ClarificationExchange>
}
