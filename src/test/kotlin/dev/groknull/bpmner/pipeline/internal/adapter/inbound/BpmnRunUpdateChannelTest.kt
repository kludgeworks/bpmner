/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.channel.ProgressOutputChannelEvent
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.ux.form.Form
import com.embabel.ux.form.RadioGroup
import com.embabel.ux.form.RadioOption
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.pipeline.RunUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration

/**
 * Covers only the Embabel-facing half of the ACL: [OutputChannel][com.embabel.agent.api.channel.OutputChannel]
 * / [AgenticEventListener][com.embabel.agent.api.event.AgenticEventListener] translation. The
 * bpmner `@DomainEvent` milestone listeners live in [BpmnMilestoneEventListener] and are covered
 * by `BpmnMilestoneEventListenerTest`.
 */
class BpmnRunUpdateChannelTest {
    private val registry = RunUpdateSinkRegistry()
    private val channel = BpmnRunUpdateChannel(registry)

    @Test
    fun `send with a ProgressOutputChannelEvent narrates in the last-known phase`() {
        registry.emit("p1", RunPhase.LAYOUT, ArtifactState.XML_DRAFT, "layout milestone")

        channel.send(ProgressOutputChannelEvent("p1", "narration text"))

        val updates = registry.subscribe("p1").take(2).collectList().block(TIMEOUT)!!
        assertEquals("narration text", updates[1].summary)
        assertEquals(RunPhase.LAYOUT, updates[1].phase)
    }

    @Test
    fun `send ignores non-progress OutputChannelEvent kinds`() {
        // No update should be emitted for a bare processId with no known phase yet.
        channel.send(ProgressOutputChannelEvent("untouched", "ignored"))
        // Belt-and-braces: a MessageOutputChannelEvent (not Progress) must be a no-op too.
        val message = com.embabel.agent.api.channel.MessageOutputChannelEvent(
            "untouched",
            com.embabel.chat.UserMessage("hi"),
        )
        channel.send(message)

        // ProgressOutputChannelEvent DOES emit — sanity check the counter really moved once,
        // and only once, confirming the Message event above produced nothing.
        registry.emit("untouched", RunPhase.READINESS, ArtifactState.NONE, "baseline")
        val updates = registry.subscribe("untouched").take(1).collectList().block(TIMEOUT)!!
        assertEquals(1L, updates.single().seq)
    }

    @Test
    fun `onProcessEvent with AgentProcessWaitingEvent emits AWAITING_INPUT with round and options`() {
        val process = processWithForm("proc-w1", "What starts the process?", listOf("Message", "Timer"))

        channel.onProcessEvent(AgentProcessWaitingEvent(process))

        val update = registry.subscribe("proc-w1").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.AWAITING_INPUT, update.phase)
        assertEquals(ArtifactState.NONE, update.artifactState)
        assertEquals("What starts the process?", update.summary)
        assertEquals("1", update.detail["round"])
        assertEquals("3", update.detail["maxRounds"])
        assertEquals("Message|Timer", update.detail["options"])
    }

    @Test
    fun `a second AgentProcessWaitingEvent for the same process increments the round`() {
        val process = processWithForm("proc-w2", "Second question?")

        channel.onProcessEvent(AgentProcessWaitingEvent(process))
        channel.onProcessEvent(AgentProcessWaitingEvent(process))

        val updates = registry.subscribe("proc-w2").take(2).collectList().block(TIMEOUT)!!
        assertEquals("1", updates[0].detail["round"])
        assertEquals("2", updates[1].detail["round"])
    }

    @Test
    fun `AgentProcessWaitingEvent with no FormBindingRequest is a no-op`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-no-form")
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessWaitingEvent(process))

        registry.emit("proc-no-form", RunPhase.READINESS, ArtifactState.NONE, "baseline")
        val updates = registry.subscribe("proc-no-form").take(1).collectList().block(TIMEOUT)!!
        assertEquals(1L, updates.single().seq, "waiting event must not have emitted anything")
    }

    @Test
    fun `AgentProcessFailedEvent emits a terminal FAILED update with NONE artifact state`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-failed")

        channel.onProcessEvent(AgentProcessFailedEvent(process))

        val update = registry.subscribe("proc-failed").collectList().block(TIMEOUT)!!.single()
        assertTrue(update is RunUpdate.Terminal)
        val terminal = update as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
        assertEquals(ArtifactState.NONE, terminal.artifactState)
    }

    @Test
    fun `AgentProcessCompletedEvent with no BpmnResult on the blackboard emits a terminal FAILED update`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-no-result")
        `when`(process.last(BpmnResult::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        val terminal = registry.subscribe("proc-no-result").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
    }

    @Test
    fun `AgentProcessCompletedEvent with a GENERATED BpmnResult emits a terminal COMPLETED FINAL update`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-generated")
        `when`(process.last(BpmnResult::class.java)).thenReturn(
            BpmnResult(outputFile = null, status = BpmnGenerationStatus.GENERATED, xml = "<xml/>"),
        )

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        val terminal = registry.subscribe("proc-generated").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.COMPLETED, terminal.outcome)
        assertEquals(ArtifactState.FINAL, terminal.artifactState)
        assertEquals("GENERATED", terminal.detail["status"])
    }

    @Test
    fun `a VALIDATION_FAILED BpmnResult emits a terminal FAILED DIAGNOSTIC update with a diagnostics summary`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-valfail")
        `when`(process.last(BpmnResult::class.java)).thenReturn(
            BpmnResult(
                outputFile = null,
                status = BpmnGenerationStatus.VALIDATION_FAILED,
                xml = "<xml/>",
                validationDiagnostics = listOf(
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.XSD,
                        message = "element is not well-formed",
                        severity = BpmnDiagnosticSeverity.ERROR,
                    ),
                ),
            ),
        )

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        val terminal = registry.subscribe("proc-valfail").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
        assertEquals(ArtifactState.DIAGNOSTIC, terminal.artifactState)
        assertTrue(terminal.detail["diagnostics"]?.contains("element is not well-formed") == true)
    }

    @Test
    fun `AgentProcessFailedEvent takes priority over the broader Finished branch (no double emission)`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("proc-priority")

        // A failed event must route to onFailed exactly once — not also fall through as if it
        // were a generic AgentProcessFinishedEvent.
        channel.onProcessEvent(AgentProcessFailedEvent(process))

        val updates = registry.subscribe("proc-priority").collectList().block(TIMEOUT)!!
        assertEquals(1, updates.size, "exactly one terminal update, never double-delivered")
    }

    private fun processWithForm(
        id: String,
        prompt: String,
        options: List<String> = emptyList(),
    ): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn(id)
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
            Form(prompt, controls, "f1"),
            dev.groknull.bpmner.readiness.BpmnClarificationAnswers::class.java,
        )
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(form)
        return process
    }

    private companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
