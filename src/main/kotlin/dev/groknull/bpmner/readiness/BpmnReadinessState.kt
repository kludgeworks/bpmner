/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.core.BpmnRequest
import jakarta.validation.Valid

@JsonClassDescription("BPMN request that has passed readiness assessment and may enter generation")
data class ReadyBpmnContext(
    @field:Valid
    @get:JsonPropertyDescription("Resolved BPMN generation request")
    val request: BpmnRequest,
    @field:Valid
    @get:JsonPropertyDescription("Readiness assessment that approved generation")
    val assessment: ProcessInputAssessment,
)

@JsonClassDescription("Current readiness state for interactive BPMN generation")
data class BpmnReadinessState(
    @field:Valid
    @get:JsonPropertyDescription("Resolved BPMN generation request")
    val request: BpmnRequest,
    @field:Valid
    @get:JsonPropertyDescription("Latest readiness assessment")
    val assessment: ProcessInputAssessment,
    @get:JsonPropertyDescription("Number of clarification rounds already submitted")
    val clarificationRound: Int = 0,
)

@JsonClassDescription("User answers to BPMN readiness clarification questions")
data class BpmnClarificationAnswers(
    @get:JsonPropertyDescription("Plain-language answers to the clarification questions shown in the form title")
    val answers: String,
)
