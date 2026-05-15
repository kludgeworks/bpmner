package dev.groknull.bpmner.core

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.GenerationMode
import dev.groknull.bpmner.generation.generationPrompt
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
                relatedMissingAreas = listOf(MissingProcessArea.START_TRIGGER),
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

        val contribution = request.contribution()

        assertFalse(contribution.contains("q-trigger"), "contribution should not mention question id")
        assertFalse(contribution.contains("What starts the process?"), "contribution should not include question text")
        assertFalse(
            contribution.contains("The customer submits an order."),
            "contribution should not include answer text",
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
