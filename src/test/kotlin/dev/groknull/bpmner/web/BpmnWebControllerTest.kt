package dev.groknull.bpmner.web

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus

class BpmnWebControllerTest {
    private val agentPlatform = mock(AgentPlatform::class.java)
    private val controller = BpmnWebController(agentPlatform)

    @Test
    fun `starts generation asynchronously and returns process info`() {
        val request = WebGenerationRequest(processDescription = "test process")

        val agent = mock(Agent::class.java)
        `when`(agent.name).thenReturn("generateBpmn")
        `when`(agentPlatform.agents()).thenReturn(listOf(agent))

        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn("test-process-123")

        `when`(
            agentPlatform.createAgentProcess(
                eq(agent) ?: agent,
                any(ProcessOptions::class.java) ?: ProcessOptions(),
                any<Map<String, Any>>() ?: emptyMap(),
            ),
        ).thenReturn(process)

        val response = controller.startGeneration(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals("test-process-123", body.processId)
        assertEquals("/api/bpmn/generations/test-process-123", body.statusUrl)
        assertEquals("/events/process/test-process-123", body.sseUrl)

        verify(agentPlatform).start(process)
    }
}
