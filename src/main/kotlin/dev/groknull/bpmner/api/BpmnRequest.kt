/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Annotation-free contract for a BPMN generation request. The concrete `domain.BpmnRequest`
 * data class implements this interface. Prompt-contribution behaviour lives in the `domain`
 * kernel as a pure `String` extension (`styleGuideContribution()`); slices wrap it locally
 * with `PromptContributor.fixed(...)` — `api/` knows nothing about Embabel (ADR-21 Track A).
 */
interface BpmnRequest {
    val processDescription: String
    val styleGuide: String?
    val outputFile: String?
    val mode: GenerationMode
    val clarificationHistory: List<ClarificationExchange>
}
