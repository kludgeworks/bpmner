/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessCompletedEvent
import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.telemetry.BpmnResultEvent
import dev.groknull.bpmner.telemetry.BpmnStageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher

class BpmnResultEventPublisherTest {

    private val published = mutableListOf<Any>()
    private val publisher = BpmnResultEventPublisher(ApplicationEventPublisher { published.add(it) })

    // -------------------------------------------------------------------------
    // Happy path: BpmnResult present on the blackboard
    // -------------------------------------------------------------------------

    @Test
    fun `publishes BpmnResultEvent with GENERATED status and no alignment fields`() {
        val result = BpmnResult(outputFile = null, status = BpmnGenerationStatus.GENERATED, xml = "<bpmn/>")
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(result)))

        assertEquals(1, published.size)
        val event = published[0] as BpmnResultEvent
        assertEquals("GENERATED", event.resultStatus)
        assertNull(event.alignmentVerdict)
        assertNull(event.alignmentReport)
    }

    @Test
    fun `publishes BpmnResultEvent with ALIGNMENT_FAILED status and alignment fields`() {
        val report = mock(BpmnAlignmentReport::class.java)
        `when`(report.verdict).thenReturn(AlignmentVerdict.FAILED)
        `when`(report.rationale).thenReturn("Contract item 'Approve order' not found in diagram")
        val result = BpmnResult(
            outputFile = null,
            status = BpmnGenerationStatus.ALIGNMENT_FAILED,
            alignmentReport = report,
        )
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(result)))

        assertEquals(1, published.size)
        val event = published[0] as BpmnResultEvent
        assertEquals("ALIGNMENT_FAILED", event.resultStatus)
        assertEquals("FAILED", event.alignmentVerdict)
        assertEquals("Contract item 'Approve order' not found in diagram", event.alignmentReport)
    }

    @Test
    fun `publishes BpmnResultEvent with NEEDS_CLARIFICATION status`() {
        val result = BpmnResult(outputFile = null, status = BpmnGenerationStatus.NEEDS_CLARIFICATION)
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(result)))

        assertEquals(1, published.size)
        assertEquals("NEEDS_CLARIFICATION", (published[0] as BpmnResultEvent).resultStatus)
    }

    @Test
    fun `publishes BpmnResultEvent with VALIDATION_FAILED status`() {
        val result = BpmnResult(outputFile = null, status = BpmnGenerationStatus.VALIDATION_FAILED)
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(result)))

        assertEquals(1, published.size)
        assertEquals("VALIDATION_FAILED", (published[0] as BpmnResultEvent).resultStatus)
    }

    @Test
    fun `publishes BpmnResultEvent with PARTIALLY_ALIGNED verdict`() {
        val report = mock(BpmnAlignmentReport::class.java)
        `when`(report.verdict).thenReturn(AlignmentVerdict.PARTIALLY_ALIGNED)
        `when`(report.rationale).thenReturn("Some items missing")
        val result = BpmnResult(
            outputFile = null,
            status = BpmnGenerationStatus.GENERATED,
            xml = "<bpmn/>",
            alignmentReport = report,
        )
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(result)))

        val event = published[0] as BpmnResultEvent
        assertEquals("PARTIALLY_ALIGNED", event.alignmentVerdict)
        assertEquals("Some items missing", event.alignmentReport)
    }

    // -------------------------------------------------------------------------
    // Guard: no BpmnResult on the blackboard (budget-exhausted / stuck runs)
    // -------------------------------------------------------------------------

    @Test
    fun `publishes nothing when no BpmnResult on the blackboard`() {
        publisher.onProcessEvent(AgentProcessCompletedEvent(processReturning(null)))

        assertEquals(0, published.size)
    }

    // -------------------------------------------------------------------------
    // Guard: non-finished events are ignored
    // -------------------------------------------------------------------------

    @Test
    fun `ignores non-AgentProcessFinishedEvent events`() {
        // BpmnStageEvent is an AbstractAgentProcessEvent but not AgentProcessFinishedEvent
        val process = mock(AgentProcess::class.java)
        publisher.onProcessEvent(BpmnStageEvent(process, "readiness", "active", "Checking readiness"))

        assertEquals(0, published.size)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun processReturning(result: BpmnResult?): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.last(BpmnResult::class.java)).thenReturn(result)
        return process
    }
}
