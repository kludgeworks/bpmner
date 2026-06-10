/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import org.springframework.stereotype.Component

@Component
internal class BpmnReadinessPromptFactory(
    private val config: BpmnConfig,
) {
    fun templateModel(request: BpmnRequest): Map<String, Any> = mapOf(
        "readyThreshold" to config.readiness.readyThreshold,
        "maxClarificationQuestions" to config.readiness.maxClarificationQuestions,
        "processDescription" to request.processDescription,
        "clarificationHistory" to request.clarificationHistory.map {
            mapOf(
                "questionId" to it.questionId,
                "questionText" to it.questionText,
                "answerText" to it.answerText,
            )
        },
    )
}
