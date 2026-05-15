package dev.groknull.bpmner.web

import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.StartGenerationOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus

class BpmnWebControllerTest {
    private val generationUseCase = mock(BpmnGenerationUseCase::class.java)
    private val controller = BpmnWebController(generationUseCase)

    @Test
    fun `accepted with relative sseUrl when readiness is ready`() {
        `when`(generationUseCase.startAsync(any() ?: BpmnGenerationInput()))
            .thenReturn(StartGenerationOutcome.Started("test-process-123"))

        val response = controller.startGeneration(WebGenerationRequest(processDescription = "Order is shipped"))

        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val body = response.body as WebGenerationResponse
        assertEquals("test-process-123", body.processId)
        assertEquals("events/process/test-process-123", body.sseUrl)
    }

    @Test
    fun `delegates process description and inline style guide to use case`() {
        `when`(generationUseCase.startAsync(any() ?: BpmnGenerationInput()))
            .thenReturn(StartGenerationOutcome.Started("p-1"))

        controller.startGeneration(
            WebGenerationRequest(
                processDescription = "Order is shipped",
                styleGuide = "Use verb-object task names",
            ),
        )

        val captor = ArgumentCaptor.forClass(BpmnGenerationInput::class.java)
        verify(generationUseCase).startAsync(captor.capture() ?: BpmnGenerationInput())
        val input = captor.value
        assertEquals("Order is shipped", input.processDescription)
        assertEquals("Use verb-object task names", input.styleGuideContent)
        assertNull(input.styleGuide)
    }

    @Test
    fun `returns 422 with report file when readiness blocks for clarification`() {
        val blocked =
            BpmnResult(
                outputFile = null,
                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                xml = null,
                reportFile = "/tmp/readiness.md",
            )
        `when`(generationUseCase.startAsync(any() ?: BpmnGenerationInput()))
            .thenReturn(StartGenerationOutcome.Blocked(blocked))

        val response = controller.startGeneration(WebGenerationRequest(processDescription = "Make it nicer"))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        val body = response.body as WebGenerationBlockedResponse
        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION.name, body.status)
        assertEquals("/tmp/readiness.md", body.reportFile)
    }

    @Test
    fun `returns 422 with status when readiness rejects non-process input`() {
        val blocked =
            BpmnResult(
                outputFile = null,
                status = BpmnGenerationStatus.NOT_A_PROCESS,
                xml = null,
                reportFile = null,
            )
        `when`(generationUseCase.startAsync(any() ?: BpmnGenerationInput()))
            .thenReturn(StartGenerationOutcome.Blocked(blocked))

        val response = controller.startGeneration(WebGenerationRequest(processDescription = "Cherry blossoms drift"))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        val body = response.body as WebGenerationBlockedResponse
        assertEquals(BpmnGenerationStatus.NOT_A_PROCESS.name, body.status)
        assertNull(body.reportFile)
    }
}
