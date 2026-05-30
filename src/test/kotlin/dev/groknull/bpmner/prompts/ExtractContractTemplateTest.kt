/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.prompts

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Substring coverage for the contract-extraction template. Mirrors the assertions from the
 * legacy BpmnContractPromptFactoryTest against the rendered template output.
 */
class ExtractContractTemplateTest {
    private val renderer = JinjavaTemplateRenderer()
    private val config = BpmnConfig().contract

    @Test
    fun `template includes structured output and no-invention instructions`() {
        val prompt = render(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("Return only a structured FlatProcessContract object."))
        assertTrue(prompt.contains("Do not invent actors"))
        assertTrue(prompt.contains("must list at least one entry in its `sourceIds` field"))
        assertTrue(prompt.contains("ContractAssumption"))
    }

    @Test
    fun `template includes original input text, rationale, and missing areas`() {
        val prompt = render(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("When a customer submits an order, ship it."))
        assertTrue(prompt.contains("One actor responsibility is underspecified."))
        assertTrue(prompt.contains(MissingProcessArea.ACTOR_RESPONSIBILITY.name))
        assertTrue(prompt.contains("ev1: Ship approved order"))
    }

    @Test
    fun `template includes clarification answers when supplied`() {
        val history = listOf(
            ClarificationExchange(
                questionId = "q1",
                questionText = "Who approves the order?",
                answerText = "The fulfilment manager.",
            ),
        )

        val prompt = render(request(), assessment(), clarificationHistory = history)

        assertTrue(prompt.contains("[q1] Q: Who approves the order?"))
        assertTrue(prompt.contains("A: The fulfilment manager."))
    }

    @Test
    fun `template explains the three branch kinds with worked examples`() {
        val prompt = render(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("CONDITIONAL"))
        assertTrue(prompt.contains("DEFAULT"))
        assertTrue(prompt.contains("UNCONDITIONAL"))
        assertTrue(prompt.contains("{kind: \"CONDITIONAL\""))
        assertTrue(prompt.contains("{kind: \"DEFAULT\""))
        assertTrue(prompt.contains("{kind: \"UNCONDITIONAL\""))
        assertTrue(prompt.contains("otherwise"))
        assertTrue(prompt.contains("the catch-all"))
        assertTrue(prompt.contains("for every other case"))
        assertTrue(prompt.contains("At most one DEFAULT branch per decision"))
        assertTrue(prompt.contains("CONDITIONAL and DEFAULT only on EXCLUSIVE decisions"))
        assertTrue(prompt.contains("UNCONDITIONAL only on PARALLEL decisions"))
    }

    @Test
    fun `template omits clarification section when history is empty`() {
        val prompt = render(request(), assessment(), clarificationHistory = emptyList())
        assertTrue(!prompt.contains("Clarification answers"))
    }

    @Test
    fun `template includes style guide when supplied`() {
        val styled = request().copy(styleGuide = "Activity names must be verb-noun.")

        val prompt = render(styled, assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("Style guide"))
        assertTrue(prompt.contains("Activity names must be verb-noun."))
    }

    private fun render(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        clarificationHistory: List<ClarificationExchange>,
    ): String = renderer.renderLoadedTemplate("bpmner/extract_contract", model(request, assessment, clarificationHistory))

    private fun model(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
        clarificationHistory: List<ClarificationExchange>,
    ): Map<String, Any> = mapOf(
        "maxAssumptions" to config.maxAssumptions,
        "rationale" to assessment.rationale,
        "missingAreas" to assessment.missingAreas.map { it.name },
        "evidence" to assessment.evidence.map {
            mapOf("id" to it.id, "text" to it.text)
        },
        "clarificationHistory" to clarificationHistory.map {
            mapOf(
                "questionId" to it.questionId,
                "questionText" to it.questionText,
                "answerText" to it.answerText,
            )
        },
        "styleGuide" to (request.styleGuide ?: ""),
        "processDescription" to request.processDescription,
    )

    private fun request() = BpmnRequest(processDescription = "When a customer submits an order, ship it.")

    private fun assessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
        overallScore = 60,
        dimensions = emptyList(),
        missingAreas = listOf(MissingProcessArea.ACTOR_RESPONSIBILITY),
        evidence = listOf(
            SourceEvidence(
                id = "ev1",
                text = "Ship approved order",
                sourceType = EvidenceSourceType.ORIGINAL_INPUT,
            ),
        ),
        rationale = "One actor responsibility is underspecified.",
    )
}
