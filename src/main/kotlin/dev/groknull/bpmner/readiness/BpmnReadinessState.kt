/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.bpmn.BpmnRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank

data class ReadyBpmnContext(
    @field:Valid
    val request: BpmnRequest,
    @field:Valid
    val assessment: ProcessInputAssessment,
)

@JsonClassDescription("User answers to BPMN readiness clarification questions")
data class BpmnClarificationAnswers(
    @field:NotBlank
    @get:JsonPropertyDescription("Plain-language answers to the clarification questions shown in the form title")
    val answers: String,
)
