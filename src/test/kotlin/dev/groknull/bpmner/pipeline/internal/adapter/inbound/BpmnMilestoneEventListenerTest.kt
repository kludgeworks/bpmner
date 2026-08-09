/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.authoring.BpmnGraphComposedEvent
import dev.groknull.bpmner.authoring.BpmnRunAbortedEvent
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.OwnedElementGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import dev.groknull.bpmner.contract.BpmnContractExtractedEvent
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.layout.BpmnLayoutCompletedEvent
import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.pipeline.RunUpdate
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Duration

/**
 * Covers only the bpmner-facing half of the ACL: the `@DomainEvent` milestone listeners in
 * [BpmnMilestoneEventListener]. The Embabel [OutputChannel][com.embabel.agent.api.channel.OutputChannel]
 * / [AgenticEventListener][com.embabel.agent.api.event.AgenticEventListener] translation is
 * covered by `BpmnRunUpdateChannelTest`.
 */
class BpmnMilestoneEventListenerTest {
    private val registry = RunUpdateSinkRegistry()
    private val listener = BpmnMilestoneEventListener(registry)

    // Every event carries processId (producer-captured); listeners never call AgentProcess.get().
    @Test
    fun `onReadinessAssessed emits a READINESS update using the event's processId`() {
        listener.onReadinessAssessed(
            BpmnReadinessAssessedEvent(
                request = BpmnRequest(processDescription = "x"),
                assessment = readyAssessment(),
                processId = "proc-readiness",
            ),
        )

        val update = registry.subscribe("proc-readiness").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.READINESS, update.phase)
        assertEquals(ArtifactState.NONE, update.artifactState)
    }

    @Test
    fun `onReadinessAssessed drops (not throws) when the event carries no processId`() {
        // A producer bug (AgentProcess.get() returned null at publish time) — must log+drop,
        // never throw, and never emit into some other process's sink.
        listener.onReadinessAssessed(
            BpmnReadinessAssessedEvent(request = BpmnRequest(processDescription = "x"), assessment = readyAssessment()),
        )
    }

    @Test
    fun `onContractExtracted emits a CONTRACT update using the event's processId`() {
        listener.onContractExtracted(
            BpmnContractExtractedEvent(
                contract = validContract(),
                processId = "proc-contract",
            ),
        )

        val update = registry.subscribe("proc-contract").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.CONTRACT, update.phase)
        assertEquals(ArtifactState.NONE, update.artifactState)
        assertEquals("Extracted the process contract.", update.summary)
        assertEquals("0", update.detail["issueCount"])
    }

    @Test
    fun `onGraphComposed emits an OUTLINE GRAPH_DRAFT update using the event's processId`() {
        listener.onGraphComposed(
            BpmnGraphComposedEvent(
                graph = minimalGraph(),
                processId = "proc-graph",
            ),
        )

        val update = registry.subscribe("proc-graph").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.OUTLINE, update.phase)
        assertEquals(ArtifactState.GRAPH_DRAFT, update.artifactState)
        assertEquals("2", update.detail["nodeCount"])
        assertEquals("1", update.detail["edgeCount"])
    }

    @Test
    fun `onGenerated emits a DRAFT XML_DRAFT update using the event's processId`() {
        listener.onGenerated(
            BpmnGeneratedEvent(
                request = BpmnRequest(processDescription = "x"),
                rendered = mock(RenderedBpmn::class.java),
                processId = "proc-generated",
            ),
        )

        val update = registry.subscribe("proc-generated").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.DRAFT, update.phase)
        assertEquals(ArtifactState.XML_DRAFT, update.artifactState)
    }

    @Test
    fun `onValidationFailed and onValidationPassed use the event's own processId`() {
        listener.onValidationFailed(
            BpmnValidationFailedEvent(
                request = BpmnRequest(processDescription = "x"),
                xml = "<xml/>",
                diagnostics = listOf(
                    BpmnDiagnostic(source = BpmnDiagnosticSource.GRAPH, message = "m", severity = BpmnDiagnosticSeverity.ERROR),
                ),
                attemptNumber = 1,
                repairAttempts = 0,
                processId = "proc-via-event",
            ),
        )
        listener.onValidationPassed(
            BpmnValidationPassedEvent(
                request = BpmnRequest(processDescription = "x"),
                xml = "<xml/>",
                repairAttempts = 1,
                processId = "proc-via-event",
            ),
        )

        val updates = registry.subscribe("proc-via-event").take(2).collectList().block(TIMEOUT)!!
        assertEquals(RunPhase.VALIDATION, updates[0].phase)
        assertEquals(ArtifactState.DIAGNOSTIC, updates[0].artifactState)
        assertEquals("1", updates[0].detail["graphIssues"])
        assertEquals(RunPhase.VALIDATION, updates[1].phase)
        assertEquals(ArtifactState.XML_DRAFT, updates[1].artifactState)
    }

    @Test
    fun `onLayoutCompleted emits a LAYOUT update using the event's processId`() {
        listener.onLayoutCompleted(BpmnLayoutCompletedEvent(xml = "<xml/>", processId = "proc-layout"))

        val update = registry.subscribe("proc-layout").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.LAYOUT, update.phase)
        assertEquals(ArtifactState.XML_DRAFT, update.artifactState)
    }

    @Test
    fun `onAlignmentChecked emits an ALIGNMENT update using the event's processId`() {
        listener.onAlignmentChecked(
            BpmnAlignmentCheckedEvent(
                request = BpmnRequest(processDescription = "x"),
                report = alignedReport(),
                processId = "proc-alignment",
            ),
        )

        val update = registry.subscribe("proc-alignment").take(1).collectList().block(TIMEOUT)!!.single()
        assertEquals(RunPhase.ALIGNMENT, update.phase)
        assertEquals(ArtifactState.XML_DRAFT, update.artifactState)
    }

    @Test
    fun `onContractExtracted onGraphComposed onGenerated onLayoutCompleted and onAlignmentChecked drop with no processId`() {
        listener.onContractExtracted(BpmnContractExtractedEvent(contract = validContract()))
        listener.onGraphComposed(BpmnGraphComposedEvent(graph = minimalGraph()))
        listener.onGenerated(
            BpmnGeneratedEvent(request = BpmnRequest(processDescription = "x"), rendered = mock(RenderedBpmn::class.java)),
        )
        listener.onLayoutCompleted(BpmnLayoutCompletedEvent(xml = "<xml/>"))
        listener.onAlignmentChecked(
            BpmnAlignmentCheckedEvent(
                request = BpmnRequest(processDescription = "x"),
                report = mock(BpmnAlignmentReport::class.java),
            ),
        )
        // No assertion beyond "did not throw" — requireProcessId's contract for producer bugs.
    }

    private fun alignedReport(): BpmnAlignmentReport = BpmnAlignmentReport(
        verdict = AlignmentVerdict.ALIGNED,
        bpmnSummary = BpmnDefinitionSummary(
            processId = "Process_1",
            processName = "Ship order",
            elements = listOf(BpmnSummaryElement(id = "StartEvent_1", type = "startEvent")),
        ),
        issues = emptyList(),
        rationale = "Fully aligned.",
    )

    private fun validContract(): ValidatedProcessContract = ValidatedProcessContract.of(
        contract = mock(ProcessContract::class.java),
        report = ContractValidationReport(issues = emptyList()),
    )!!

    private fun minimalGraph(): LaidOutProcessGraph = LaidOutProcessGraph(
        ownedGraph = mock(OwnedElementGraph::class.java),
        definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Handle request",
            nodes = listOf(
                BpmnStartEvent("StartEvent_1", "Request received"),
                BpmnEndEvent("EndEvent_1", "Request completed"),
            ),
            sequences = listOf(BpmnEdge("Flow_1", "StartEvent_1", "EndEvent_1")),
        ),
    )

    @Test
    fun `a run that dies outside the plan still ends with a terminal update`() {
        // An exception raised by an action escapes the process run loop before a terminal status
        // is set, so no lifecycle event is emitted. Without this backstop the run goes silent.
        listener.onRunAborted(BpmnRunAbortedEvent(processId = "proc-abort", detail = "boom"))

        val terminal = registry.subscribe("proc-abort").collectList().block(TIMEOUT)!!.single()
            as RunUpdate.Terminal
        assertEquals(RunOutcome.FAILED, terminal.outcome)
        assertEquals("boom", terminal.detail["failureDetail"])
    }

    private fun readyAssessment(): ProcessInputAssessment = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(
            ReadinessDimensionScore(dimension = ReadinessDimension.ACTORS_ROLES, score = 90, rationale = "clear"),
        ),
        rationale = "Input is sufficient.",
    )

    private companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
