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
