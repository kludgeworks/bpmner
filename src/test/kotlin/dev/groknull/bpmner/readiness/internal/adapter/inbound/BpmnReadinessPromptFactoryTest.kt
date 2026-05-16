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

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.ReadinessDimension
import kotlin.test.Test
import kotlin.test.assertTrue

class BpmnReadinessPromptFactoryTest {
    @Test
    fun `prompt includes structured output and do not invent constraints`() {
        val prompt =
            BpmnReadinessPromptFactory(BpmnConfig().readiness)
                .prompt(BpmnRequest("When an order is submitted, review it, then ship it."))

        assertTrue(prompt.contains("Return only a structured ProcessInputAssessment object."))
        assertTrue(prompt.contains("Do not invent actors"))
        assertTrue(prompt.contains("Mark unsupported facts as missing"))
        assertTrue(prompt.contains(ReadinessDimension.START_TRIGGER.name))
    }

    @Test
    fun `prompt scopes workflow types beyond business processes`() {
        val prompt =
            BpmnReadinessPromptFactory(BpmnConfig().readiness)
                .prompt(BpmnRequest("When an order is submitted, review it, then ship it."))

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
            "prompt should no longer reference the retired NOT_A_PROCESS verdict",
        )
    }

    @Test
    fun `prompt includes prior clarification answers`() {
        val prompt =
            BpmnReadinessPromptFactory(BpmnConfig().readiness)
                .prompt(
                    BpmnRequest(
                        processDescription = "Ship order",
                        clarificationHistory =
                            listOf(
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
}
