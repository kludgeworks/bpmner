/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
