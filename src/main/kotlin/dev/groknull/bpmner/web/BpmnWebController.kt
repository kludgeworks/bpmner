package dev.groknull.bpmner.web

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class WebGenerationRequest(
    val processDescription: String,
    val styleGuide: String? = null,
)

data class WebGenerationResponse(
    val processId: String,
    val statusUrl: String,
    val sseUrl: String,
)

@RestController
@RequestMapping("/api/bpmn")
@Profile("web")
class BpmnWebController(
    private val agentPlatform: AgentPlatform,
) {
    @PostMapping("/generations")
    fun startGeneration(@RequestBody request: WebGenerationRequest): ResponseEntity<WebGenerationResponse> {
        val bpmnRequest = BpmnRequest(
            processDescription = request.processDescription,
            styleGuide = request.styleGuide,
            outputFile = null, // No output file for web mode
        )

        val options = ProcessOptions()

        // We use AgentPlatformTypedOps to create an agent process for the BpmnResult goal.
        val agent = agentPlatform.agents().first { it.name == "generateBpmn" }
        val process = agentPlatform.createAgentProcess(agent, options, mapOf("request" to bpmnRequest))
        agentPlatform.start(process)

        val response = WebGenerationResponse(
            processId = process.id,
            statusUrl = "/api/bpmn/generations/${process.id}",
            sseUrl = "/events/process/${process.id}",
        )
        return ResponseEntity.ok(response)
    }
}
