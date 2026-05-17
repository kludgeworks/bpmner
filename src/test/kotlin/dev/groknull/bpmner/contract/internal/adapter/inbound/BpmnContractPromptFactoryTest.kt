/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

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

class BpmnContractPromptFactoryTest {
    private val factory = BpmnContractPromptFactory(BpmnConfig().contract)

    @Test
    fun `prompt includes structured output and no-invention instructions`() {
        val prompt = factory.prompt(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("Return only a structured ProcessContract object."))
        assertTrue(prompt.contains("Do not invent actors"))
        assertTrue(prompt.contains("must carry at least one TraceLink"))
        assertTrue(prompt.contains("ContractAssumption"))
    }

    @Test
    fun `prompt includes original input text, rationale, and missing areas`() {
        val prompt = factory.prompt(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("When a customer submits an order, ship it."))
        assertTrue(prompt.contains("One actor responsibility is underspecified."))
        assertTrue(prompt.contains(MissingProcessArea.ACTOR_RESPONSIBILITY.name))
        assertTrue(prompt.contains("ev1: Ship approved order"))
    }

    @Test
    fun `prompt includes clarification answers when supplied`() {
        val history =
            listOf(
                ClarificationExchange(
                    questionId = "q1",
                    questionText = "Who approves the order?",
                    answerText = "The fulfilment manager.",
                ),
            )

        val prompt = factory.prompt(request(), assessment(), clarificationHistory = history)

        assertTrue(prompt.contains("[q1] Q: Who approves the order?"))
        assertTrue(prompt.contains("A: The fulfilment manager."))
    }

    @Test
    fun `prompt omits clarification section when history is empty`() {
        val prompt = factory.prompt(request(), assessment(), clarificationHistory = emptyList())

        assertTrue(!prompt.contains("Clarification answers"))
    }

    @Test
    fun `prompt includes style guide when supplied`() {
        val styled = request().copy(styleGuide = "Activity names must be verb-noun.")

        val prompt = factory.prompt(styled, assessment(), clarificationHistory = emptyList())

        assertTrue(prompt.contains("Style guide"))
        assertTrue(prompt.contains("Activity names must be verb-noun."))
    }

    private fun request() = BpmnRequest(processDescription = "When a customer submits an order, ship it.")

    private fun assessment() =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 60,
            dimensions = emptyList(),
            missingAreas = listOf(MissingProcessArea.ACTOR_RESPONSIBILITY),
            evidence =
                listOf(
                    SourceEvidence(
                        id = "ev1",
                        text = "Ship approved order",
                        sourceType = EvidenceSourceType.ORIGINAL_INPUT,
                    ),
                ),
            rationale = "One actor responsibility is underspecified.",
        )
}
