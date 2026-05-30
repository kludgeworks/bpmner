/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the readiness assess-input template. Mirrors the legacy
 * BpmnReadinessPromptFactoryTest's assertions against the rendered template output.
 */
class AssessReadinessTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val config = BpmnConfig().readiness

    @Test
    fun `template includes structured output and do-not-invent constraints`() {
        val prompt = render(BpmnRequest("When an order is submitted, review it, then ship it."))

        assertTrue(prompt.contains("Return only a structured ProcessInputAssessment object."))
        assertTrue(prompt.contains("Do not invent actors"))
        assertTrue(prompt.contains("Mark unsupported facts as missing"))
        assertTrue(prompt.contains(ReadinessDimension.START_TRIGGER.name))
    }

    @Test
    fun `template scopes workflow types beyond business processes`() {
        val prompt = render(BpmnRequest("When an order is submitted, review it, then ship it."))

        assertTrue(
            prompt.contains("automated", ignoreCase = true),
            "prompt should name automated workflows as in scope",
        )
        assertTrue(
            prompt.contains("technical", ignoreCase = true),
            "prompt should name technical workflows as in scope",
        )
        assertTrue(
            prompt.contains("software agents", ignoreCase = true),
            "prompt should clarify that software agents are valid BPMN actors",
        )
        assertTrue(
            prompt.contains("never penalise a workflow solely for being automated, technical, or non-business"),
            "prompt should explicitly forbid rejecting solely on domain (e.g. automated/technical)",
        )
        assertTrue(
            !prompt.contains("NOT_A_PROCESS"),
            "prompt should not reference the retired NOT_A_PROCESS verdict",
        )
    }

    @Test
    fun `template includes prior clarification answers when supplied`() {
        val prompt = render(
            BpmnRequest(
                processDescription = "Ship order",
                clarificationHistory = listOf(
                    ClarificationExchange(
                        questionId = "q1",
                        questionText = "What starts the process?",
                        answerText = "The customer submits an order.",
                    ),
                ),
            ),
        )

        assertTrue(prompt.contains("Prior clarification answers"))
        assertTrue(prompt.contains("[q1] Q: What starts the process?"))
        assertTrue(prompt.contains("A: The customer submits an order."))
    }

    @Test
    fun `template omits clarification section when history is empty`() {
        val prompt = render(BpmnRequest("Ship order"))
        assertTrue(!prompt.contains("Prior clarification answers"))
    }

    private fun render(request: BpmnRequest): String {
        return renderer.renderLoadedTemplate("bpmner/assess_readiness", model(request))
    }

    private fun model(request: BpmnRequest): Map<String, Any> = mapOf(
        "readyThreshold" to config.readyThreshold,
        "maxClarificationQuestions" to config.maxClarificationQuestions,
        "dimensions" to ReadinessDimension.entries.map { it.name },
        "missingAreas" to MissingProcessArea.entries.map { it.name },
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
