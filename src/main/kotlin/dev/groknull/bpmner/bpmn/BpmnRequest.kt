/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Annotation-free contract for a BPMN generation request. The concrete `bpmn.internal.model.BpmnRequest`
 * data class implements this interface. Prompt-contribution behaviour lives in the `bpmn`
 * kernel as a pure `String` extension (`styleGuideContribution()`); slices wrap it locally
 * with `PromptContributor.fixed(...)` — `bpmn/` knows nothing about Embabel (ADR-21 Track A).
 */
interface BpmnRequest {
    val processDescription: String
    val styleGuide: String?
    val outputFile: String?
    val mode: GenerationMode
    val clarificationHistory: List<ClarificationExchange>
}
