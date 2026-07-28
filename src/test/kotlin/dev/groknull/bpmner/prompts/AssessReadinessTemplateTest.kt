/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.readiness.ClarificationExchange
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the readiness assess-input template. Mirrors the legacy
 * BpmnReadinessPromptFactoryTest's assertions against the rendered template output.
 */
class AssessReadinessTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val config = BpmnReadinessThresholdsConfig()

    @Test
    fun `template includes structured output and do-not-invent constraints`() {
        val prompt = render(BpmnRequest("When an order is submitted, review it, then ship it."))

        assertTrue(prompt.contains("Return only a structured ProcessInputAssessment object."))
        assertTrue(prompt.contains("Do not invent process facts"))
        assertTrue(prompt.contains("Mark a process fact as missing only when"))
        // ReadinessDimension enum names reach the LLM via the JSON schema, not the prompt prose.
        // The per-dimension calibration paragraph stays — anchor on the most-misjudged dimension name.
        assertTrue(prompt.contains("BPMN_SUITABILITY"))
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

    @Test
    fun `template limits clarification to material modelling decisions`() {
        val prompt = render(BpmnRequest("Applications are reviewed and may proceed to an interview."))

        assertTrue(prompt.contains("would materially change participants, activities, ordering"))
        assertTrue(prompt.contains("Supply 2–4 short answer options for every question"))
        assertTrue(prompt.contains("Never ask the user to enumerate activities, steps"))
        assertTrue(prompt.contains("If any check fails, remove the question"))
        assertTrue(prompt.contains("Refer to the relevant source statement"))
        assertTrue(prompt.contains("Ask at most 3 questions"))
        assertTrue(prompt.contains("BAD: What activities occur from reporting applications"))
        assertTrue(prompt.contains("GOOD: Can multiple applications be active concurrently"))
    }

    @Test
    fun `template permits harmless modelling defaults and inferred ordering`() {
        val prompt = render(BpmnRequest("After probation, both parties submit ratings."))

        assertTrue(prompt.contains("Infer order from ordinary language"))
        assertTrue(prompt.contains("none start event is valid"))
        assertTrue(prompt.contains("Do not ask for an explicit start trigger unless"))
        assertTrue(prompt.contains("Score modelling sufficiency"))
    }

    private fun render(request: BpmnRequest): String {
        return renderer.renderLoadedTemplate("bpmner/assess_readiness", model(request))
    }

    private fun model(request: BpmnRequest): Map<String, Any> = mapOf(
        "readyThreshold" to config.readyThreshold,
        "maxClarificationQuestions" to config.maxClarificationQuestions,
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
