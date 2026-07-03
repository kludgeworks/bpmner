/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.hitl.FormBindingRequest
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus

class BpmnWebControllerTest {
    private val generationStarter = mock(WebGenerationStarter::class.java)
    private val agentPlatform = mock(AgentPlatform::class.java)
    private val controller = BpmnWebController(generationStarter, agentPlatform)

    // -------------------------------------------------------------------------
    // POST /generations (existing tests — constructor updated to include agentPlatform)
    // -------------------------------------------------------------------------

    @Test
    fun `accepted with relative sseUrl when generation starts`() {
        `when`(generationStarter.start(any() ?: WebGenerationRequest("fallback")))
            .thenReturn("test-process-123")

        val response = controller.startGeneration(WebGenerationRequest(processDescription = "Order is shipped"))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val body = response.body!!
        assertEquals("test-process-123", body.processId)
        assertEquals("events/process/test-process-123", body.sseUrl)
    }

    @Test
    fun `delegates process description and inline style guide to starter`() {
        `when`(generationStarter.start(any() ?: WebGenerationRequest("fallback")))
            .thenReturn("p-1")

        controller.startGeneration(
            WebGenerationRequest(
                processDescription = "Order is shipped",
                styleGuide = "Use verb-object task names",
            ),
        )

        val captor = ArgumentCaptor.forClass(WebGenerationRequest::class.java)
        verify(generationStarter).start(captor.capture() ?: WebGenerationRequest("fallback"))
        val request = captor.value
        assertEquals("Order is shipped", request.processDescription)
        assertEquals("Use verb-object task names", request.styleGuide)
    }

    // -------------------------------------------------------------------------
    // GET /generations/{id}/bpmn
    // -------------------------------------------------------------------------

    @Test
    fun `200 with xml body and attachment header when process has terminal xml`() {
        val xml = "<definitions>generated</definitions>"
        val result = BpmnResult(outputFile = null, status = BpmnGenerationStatus.GENERATED, xml = xml)
        val process = processWithResult(result)
        `when`(agentPlatform.getAgentProcess("proc-1")).thenReturn(process)

        val response = controller.downloadBpmn("proc-1")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(xml, response.body)
        val disposition = response.headers.getFirst("Content-Disposition")
        assertTrue(
            disposition?.contains("attachment") == true,
            "Content-Disposition should indicate attachment; got: $disposition",
        )
        assertTrue(
            disposition?.contains("proc-1.bpmn") == true,
            "Content-Disposition should include filename proc-1.bpmn; got: $disposition",
        )
    }

    @Test
    fun `404 when process id is unknown`() {
        `when`(agentPlatform.getAgentProcess("unknown")).thenReturn(null)

        val response = controller.downloadBpmn("unknown")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `404 when getAgentProcess throws treating it as not found`() {
        `when`(agentPlatform.getAgentProcess("bad-id")).thenThrow(RuntimeException("no such process"))

        val response = controller.downloadBpmn("bad-id")

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `409 when process is running and has no BpmnResult yet`() {
        val process = processWithResult(null)
        `when`(agentPlatform.getAgentProcess("running")).thenReturn(process)

        val response = controller.downloadBpmn("running")

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `409 when terminal BpmnResult has null xml (NEEDS_CLARIFICATION terminal)`() {
        val result = BpmnResult(outputFile = null, status = BpmnGenerationStatus.NEEDS_CLARIFICATION, xml = null)
        val process = processWithResult(result)
        `when`(agentPlatform.getAgentProcess("clarify")).thenReturn(process)

        val response = controller.downloadBpmn("clarify")

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    // -------------------------------------------------------------------------
    // POST /generations/{id}/answers
    // -------------------------------------------------------------------------

    @Test
    fun `202 when process is WAITING and form is present — bind and start are called`() {
        val answers = BpmnClarificationAnswers("The order is completed.")
        val process = waitingProcessWithForm()
        `when`(agentPlatform.getAgentProcess("waiting-proc")).thenReturn(process)

        val response = controller.submitAnswers("waiting-proc", answers)

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        // Verify bind was called with the submitted answers
        val form =
            @Suppress("UNCHECKED_CAST")
            process.last(FormBindingRequest::class.java) as FormBindingRequest<BpmnClarificationAnswers>
        verify(form).bind(answers, process)
        verify(agentPlatform).start(process)
    }

    @Test
    fun `404 when answers submitted for unknown process id`() {
        `when`(agentPlatform.getAgentProcess("unknown")).thenReturn(null)

        val response = controller.submitAnswers("unknown", BpmnClarificationAnswers("answer"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `404 when getAgentProcess throws for answers`() {
        `when`(agentPlatform.getAgentProcess("bad-id")).thenThrow(RuntimeException("not found"))

        val response = controller.submitAnswers("bad-id", BpmnClarificationAnswers("answer"))

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `409 when process is not in WAITING state`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.status).thenReturn(AgentProcessStatusCode.RUNNING)
        `when`(agentPlatform.getAgentProcess("running")).thenReturn(process)

        val response = controller.submitAnswers("running", BpmnClarificationAnswers("answer"))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    @Test
    fun `409 when no FormBindingRequest on blackboard despite WAITING status`() {
        val process = mock(AgentProcess::class.java)
        `when`(process.status).thenReturn(AgentProcessStatusCode.WAITING)
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(null)
        `when`(agentPlatform.getAgentProcess("no-form")).thenReturn(process)

        val response = controller.submitAnswers("no-form", BpmnClarificationAnswers("answer"))

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun processWithResult(result: BpmnResult?): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.last(BpmnResult::class.java)).thenReturn(result)
        return process
    }

    @Suppress("UNCHECKED_CAST")
    private fun waitingProcessWithForm(): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.status).thenReturn(AgentProcessStatusCode.WAITING)
        val form = mock(FormBindingRequest::class.java) as FormBindingRequest<BpmnClarificationAnswers>
        `when`(process.last(FormBindingRequest::class.java)).thenReturn(form)
        return process
    }
}
