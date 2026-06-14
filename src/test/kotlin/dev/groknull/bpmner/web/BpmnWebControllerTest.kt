/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus

class BpmnWebControllerTest {
    private val generationStarter = mock(WebGenerationStarter::class.java)
    private val controller = BpmnWebController(generationStarter)

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
}
