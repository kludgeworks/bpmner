/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.common.ai.prompt.PromptContributor
import dev.groknull.bpmner.authoring.generationPrompt
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.styleGuideContribution
import dev.groknull.bpmner.readiness.ClarificationExchange
import dev.groknull.bpmner.readiness.ReadinessDimension
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnRequestTest {
    private val clarifications =
        listOf(
            ClarificationExchange(
                questionId = "q-trigger",
                questionText = "What starts the process?",
                answerText = "The customer submits an order.",
                relatedMissingAreas = listOf(ReadinessDimension.START_TRIGGER),
                relatedDimensions = listOf(ReadinessDimension.START_TRIGGER),
                evidence = emptyList(),
            ),
        )

    @Test
    fun `contribution omits clarification history to avoid duplicate rendering by factories`() {
        val request =
            BpmnRequest(
                processDescription = "Ship the order",
                clarificationHistory = clarifications,
            )

        val contribution = PromptContributor.fixed(request.styleGuideContribution()).contribution()

        assertFalse(contribution.contains("q-trigger"), "contribution should not mention question id")
        assertFalse(contribution.contains("What starts the process?"), "contribution should not include question text")
        assertFalse(
            contribution.contains("The customer submits an order."),
            "contribution should not include answer text",
        )
    }

    @Test
    fun `contribution is empty without a style guide and carries only the style guide with one`() {
        // contribution() carries only the style guide. BPMN generation rules are owned by schema
        // annotations (NODE_ID_DESCRIPTION), BpmnDefinitionValidator, and generate_bpmn.jinja, so
        // they must not appear in the system-message contribution.
        assertTrue(
            PromptContributor
                .fixed(BpmnRequest(processDescription = "Ship the order").styleGuideContribution())
                .contribution()
                .isEmpty(),
            "contribution should be empty when no style guide is supplied",
        )

        val withGuide =
            BpmnRequest(
                processDescription = "Ship the order",
                styleGuide = "Use sentence case for task names.",
            ).let { PromptContributor.fixed(it.styleGuideContribution()).contribution() }

        assertTrue(withGuide.contains("Use sentence case for task names."), "style guide should be carried")
        assertFalse(
            withGuide.contains("BPMN process design expert"),
            "the BPMN-expert framing must not appear in the contribution",
        )
        assertFalse(withGuide.contains("Identity rule"), "identity rule now lives in NODE_ID_DESCRIPTION, not here")
        assertFalse(
            withGuide.contains("at least one START_EVENT"),
            "START/END requirement is enforced by BpmnDefinitionValidator, not prompt prose",
        )
    }

    @Test
    fun `generationPrompt includes clarification history for the generator agent`() {
        val request =
            BpmnRequest(
                processDescription = "Ship the order",
                clarificationHistory = clarifications,
            )

        val prompt = request.generationPrompt()

        assertTrue(prompt.contains("Ship the order"))
        assertTrue(prompt.contains("q-trigger"))
        assertTrue(prompt.contains("What starts the process?"))
        assertTrue(prompt.contains("The customer submits an order."))
    }

    @Test
    fun `generationPrompt without clarifications still renders the process description`() {
        val request = BpmnRequest(processDescription = "Ship the order")

        val prompt = request.generationPrompt()

        assertTrue(prompt.contains("Ship the order"))
        assertFalse(prompt.contains("Clarification answers:"))
    }
}
