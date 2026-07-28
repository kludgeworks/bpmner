/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.ux.form.Form
import com.embabel.ux.form.RadioGroup
import com.embabel.ux.form.RadioOption
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.telemetry.BpmnClarificationRequestEvent
import dev.groknull.bpmner.telemetry.BpmnStageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher

class BpmnClarificationRequestEventPublisherTest {

    private val published = mutableListOf<Any>()
    private val publisher =
        BpmnClarificationRequestEventPublisher(ApplicationEventPublisher { published.add(it) })

    // -------------------------------------------------------------------------
    // Happy path: WaitingEvent with a FormBindingRequest on the blackboard
    // -------------------------------------------------------------------------

    @Test
    fun `publishes BpmnClarificationRequestEvent with round=1 on first park`() {
        val process = processWithForm("What starts the process?")

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        assertEquals(1, published.size)
        val event = published[0] as BpmnClarificationRequestEvent
        assertEquals(1, event.round)
        assertEquals(3, event.maxRounds)
        assertEquals("What starts the process?", event.prompt)
    }

    @Test
    fun `publishes round=2 on second WaitingEvent for the same process id`() {
        val process = processWithForm("What is the end state?")

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))
        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        assertEquals(2, published.size)
        assertEquals(1, (published[0] as BpmnClarificationRequestEvent).round)
        assertEquals(2, (published[1] as BpmnClarificationRequestEvent).round)
    }

    @Test
    fun `publishes bounded answer options for the current form question`() {
        val prompt = "Can applications run concurrently?"
        val process = processWithForm(prompt, listOf("Concurrently", "One at a time"))

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        val event = published.single() as BpmnClarificationRequestEvent
        assertEquals(prompt, event.prompt)
        assertEquals(listOf("Concurrently", "One at a time"), event.options)
    }

    @Test
    fun `resets round counter after AgentProcessFinishedEvent so next park emits round=1`() {
        val process = processWithForm("Initial question")

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))
        publisher.onProcessEvent(AgentProcessCompletedEvent(process))
        published.clear()

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        assertEquals(1, published.size)
        assertEquals(1, (published[0] as BpmnClarificationRequestEvent).round)
    }

    @Test
    fun `resets round counter after AgentProcessFailedEvent so next park emits round=1`() {
        val process = processWithForm("Initial question")

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))
        publisher.onProcessEvent(AgentProcessFailedEvent(process))
        published.clear()

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        assertEquals(1, published.size)
        assertEquals(1, (published[0] as BpmnClarificationRequestEvent).round)
    }

    // -------------------------------------------------------------------------
    // Guard: no FormBindingRequest on the blackboard → no event
    // -------------------------------------------------------------------------

    @Test
    fun `publishes nothing when FormBindingRequest is null on the blackboard`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-null-form")
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(null)

        publisher.onProcessEvent(AgentProcessWaitingEvent(process))

        assertEquals(0, published.size)
    }

    // -------------------------------------------------------------------------
    // Guard: non-waiting events are ignored
    // -------------------------------------------------------------------------

    @Test
    fun `ignores non-WaitingEvent events`() {
        val process = mock(AgentProcess::class.java)
        // BpmnStageEvent is an AbstractAgentProcessEvent but not AgentProcessWaitingEvent
        publisher.onProcessEvent(BpmnStageEvent(process, "readiness", "active", "Checking readiness"))

        assertEquals(0, published.size)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun processWithForm(
        promptText: String,
        options: List<String> = emptyList(),
    ): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-${promptText.take(10).replace(" ", "-")}")
        val controls = options.takeIf { it.isNotEmpty() }
            ?.let { values ->
                listOf(
                    RadioGroup(
                        label = "Answer",
                        options = values.map { RadioOption(it, it) },
                        required = true,
                        id = "answers",
                    ),
                )
            }.orEmpty()
        val form = FormBindingRequest(
            Form(promptText, controls, "f1"),
            BpmnClarificationAnswers::class.java,
        )
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(form)
        return process
    }
}
