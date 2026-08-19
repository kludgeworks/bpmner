/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.channel.ProgressOutputChannelEvent
import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessStuckEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.ux.form.Form
import com.embabel.ux.form.RadioGroup
import com.embabel.ux.form.RadioOption
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.FinalValidatedBpmnXml
import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.BpmnPermalinkStore
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.pipeline.RunUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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
    private val permalinkStore = mock(BpmnPermalinkStore::class.java)
    private val channel = BpmnRunUpdateChannel(registry, permalinkStore)

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
        val process = primaryProcess("proc-no-form")
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessWaitingEvent(process))

        registry.emit("proc-no-form", RunPhase.READINESS, ArtifactState.NONE, "baseline")
        val updates = registry.subscribe("proc-no-form").take(1).collectList().block(TIMEOUT)!!
        assertEquals(1L, updates.single().seq, "waiting event must not have emitted anything")
    }

    @Test
    fun `AgentProcessFailedEvent emits a terminal FAILED update with NONE artifact state`() {
        val process = primaryProcess("proc-failed")

        channel.onProcessEvent(AgentProcessFailedEvent(process))

        val update = registry.subscribe("proc-failed").collectList().block(TIMEOUT)!!.single()
        assertTrue(update is RunUpdate.Terminal)
        val terminal = update as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
        assertEquals(ArtifactState.NONE, terminal.artifactState)
    }

    @Test
    fun `AgentProcessCompletedEvent with no BpmnResult on the blackboard emits a terminal FAILED update`() {
        val process = primaryProcess("proc-no-result")
        `when`(process.last(BpmnResult::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        val terminal = registry.subscribe("proc-no-result").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
    }

    @Test
    fun `AgentProcessCompletedEvent with a GENERATED BpmnResult emits a terminal COMPLETED FINAL update`() {
        val process = primaryProcess("proc-generated")
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
        val process = primaryProcess("proc-valfail")
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
        val process = primaryProcess("proc-priority")

        // A failed event must route to onFailed exactly once — not also fall through as if it
        // were a generic AgentProcessFinishedEvent.
        channel.onProcessEvent(AgentProcessFailedEvent(process))

        val updates = registry.subscribe("proc-priority").collectList().block(TIMEOUT)!!
        assertEquals(1, updates.size, "exactly one terminal update, never double-delivered")
    }

    @Test
    fun `a stuck process emits a terminal FAILED update`() {
        // A stuck process has no route to any goal and emits nothing further. It is not a
        // "finished" event, so without an explicit branch a subscriber waits forever.
        val process = primaryProcess("proc-stuck")

        channel.onProcessEvent(AgentProcessStuckEvent(process))

        val terminal = registry.subscribe("proc-stuck").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
    }

    @Test
    fun `only the first terminal is delivered when a run reports one more than once`() {
        // A run can reach a terminal by more than one route — its own result, a platform failure
        // event, and the abort backstop. A consumer must still see exactly one.
        val process = primaryProcess("proc-double")

        channel.onProcessEvent(AgentProcessStuckEvent(process))
        channel.onProcessEvent(AgentProcessFailedEvent(process))
        registry.emitTerminal(
            processId = "proc-double",
            artifactState = ArtifactState.NONE,
            summary = "late backstop",
            outcome = RunOutcome.FAILED,
        )

        val updates = registry.subscribe("proc-double").collectList().block(TIMEOUT)!!
        assertEquals(1, updates.size, "exactly one terminal, whichever route reported first")
        assertEquals("BPMN generation could not continue.", updates.single().summary)
    }

    @Test
    fun `a readiness sub-process finishing without a BpmnResult emits nothing`() {
        // Readiness runs as its own process on a different agent and never binds a BpmnResult,
        // so without the filter it reaches the null branch of onFinished and reports a terminal
        // FAILED against a run that is still going.
        val readiness = nonPrimaryProcess("proc-readiness-sub", "BpmnReadinessAgent", root = true)
        `when`(readiness.last(BpmnResult::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessCompletedEvent(readiness))

        // A sink with no terminal never completes, so assert by sentinel: if the event above had
        // emitted, the sentinel would not be first and would not hold seq 1.
        registry.emit("proc-readiness-sub", RunPhase.READINESS, ArtifactState.NONE, "sentinel")
        val update = registry.subscribe("proc-readiness-sub").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals("sentinel", update.summary)
        assertEquals(1L, update.seq)
    }

    @Test
    fun `a nested sub-process of the generation agent emits nothing`() {
        // The generation agent spawns nested processes (ids like "BpmnGenerationAgent >> name").
        // They share the agent name, so only the root check excludes them.
        val nested = nonPrimaryProcess("BpmnGenerationAgent >> nested", "BpmnGenerationAgent", root = false)
        `when`(nested.last(BpmnResult::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessCompletedEvent(nested))
        channel.onProcessEvent(AgentProcessFailedEvent(nested))
        channel.onProcessEvent(AgentProcessStuckEvent(nested))

        registry.emit("BpmnGenerationAgent >> nested", RunPhase.READINESS, ArtifactState.NONE, "sentinel")
        val update = registry.subscribe("BpmnGenerationAgent >> nested")
            .take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals("sentinel", update.summary)
        assertEquals(1L, update.seq)
    }

    @Test
    fun `a primary run finishing without a BpmnResult still emits a terminal FAILED`() {
        // The filter is on which process, never on the absence of a result: a primary run that
        // finishes empty is a real failure and must not be swallowed.
        val process = primaryProcess("proc-primary-empty")
        `when`(process.last(BpmnResult::class.java)).thenReturn(null)

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        val update = registry.subscribe("proc-primary-empty").collectList().block(TIMEOUT)!!.single()
        assertEquals(RunOutcome.FAILED, (update as RunUpdate.Terminal).outcome)
        assertEquals("BPMN generation did not complete.", update.summary)
    }

    @Test
    fun `a primary run finishing successfully saves XML and returns permalinkId in detail`() {
        val process = primaryProcess("proc-id-long-1234abcd")
        val result = BpmnResult(
            outputFile = "test.bpmn",
            status = BpmnGenerationStatus.GENERATED,
            xml = "<definitions/>",
        )
        `when`(process.last(BpmnResult::class.java)).thenReturn(result)

        val definition = mock(BpmnDefinition::class.java)
        `when`(definition.processName).thenReturn("Employee Onboarding Process")

        val finalXml = mock(FinalValidatedBpmnXml::class.java)
        `when`(finalXml.definition).thenReturn(definition)

        `when`(process.last(FinalValidatedBpmnXml::class.java)).thenReturn(finalXml)

        channel.onProcessEvent(AgentProcessCompletedEvent(process))

        verify(permalinkStore).save("employee-onboarding-process-proc-id-", "<definitions/>")

        val update = registry.subscribe("proc-id-long-1234abcd").collectList().block(TIMEOUT)!!.single()
        assertEquals(RunOutcome.COMPLETED, (update as RunUpdate.Terminal).outcome)
        assertEquals("employee-onboarding-process-proc-id-", update.detail["permalinkId"])
    }

    /**
     * A root process running the generation agent — the one process the browser subscribes to,
     * and the only one this channel may emit for. See [nonPrimaryProcess].
     */
    private fun primaryProcess(id: String): AgentProcess = agentProcess(id, "BpmnGenerationAgent", root = true)

    /** A process this channel must ignore: any agent but the deployed generation agent, or a
     * nested sub-process of it. */
    private fun nonPrimaryProcess(id: String, agentName: String, root: Boolean): AgentProcess =
        agentProcess(id, agentName, root)

    private fun agentProcess(id: String, agentName: String, root: Boolean): AgentProcess {
        val process = mock(AgentProcess::class.java)
        val agent = mock(Agent::class.java)
        `when`(process.id).thenReturn(id)
        `when`(agent.name).thenReturn(agentName)
        `when`(process.agent).thenReturn(agent)
        `when`(process.parentId).thenReturn(if (root) null else "parent-run")
        return process
    }

    private fun processWithForm(
        id: String,
        prompt: String,
        options: List<String> = emptyList(),
    ): AgentProcess {
        val process = primaryProcess(id)
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
