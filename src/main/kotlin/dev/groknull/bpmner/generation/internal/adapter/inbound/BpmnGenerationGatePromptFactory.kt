/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.domain.io.UserInput
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.springframework.stereotype.Component

@Component
internal class BpmnGenerationGatePromptFactory {
    fun draftBpmnRequestPrompt(userInput: UserInput): String {
        return """
            Extract a BPMN generation request from the user's shell instruction.

            Rules:
            - Put the workflow prose in processDescription when the user described the workflow directly.
            - Put a file path in processFile only when the user explicitly says the workflow is in a file.
            - Always set outputFile. If the user named a specific output file, use it exactly;
              otherwise generate a concise, lowercase, kebab-case name ending in .bpmn derived from
              the process, with no directories or spaces (e.g. purchase-order-approval.bpmn).
            - Put inline style guidance in styleGuide, or a style-guide file path in styleGuideFile.
            - Do not invent input files (processFile) or style-guide files.

            User instruction:
            ${userInput.content}

        """.trimIndent()
    }

    fun clarificationPrompt(
        assessment: ProcessInputAssessment,
        round: Int,
        maxRounds: Int,
    ): String {
        val questions =
            assessment.clarificationQuestions
                .joinToString(separator = System.lineSeparator()) { question ->
                    "- ${question.questionText}"
                }
        return "BPMN clarification round ${round + 1} of $maxRounds\n$questions"
    }
}
