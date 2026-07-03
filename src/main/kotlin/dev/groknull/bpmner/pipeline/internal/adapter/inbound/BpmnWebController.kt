/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.core.AgentPlatform
import dev.groknull.bpmner.authoring.BpmnResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.annotation.Profile
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WebGenerationRequest(
    @field:NotBlank
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    val processDescription: String,
    @field:Size(max = MAX_STYLE_GUIDE_LENGTH)
    val styleGuide: String? = null,
) {
    companion object {
        const val MAX_DESCRIPTION_LENGTH = 10_000
        const val MAX_STYLE_GUIDE_LENGTH = 20_000
    }
}

data class WebGenerationResponse(
    val processId: String,
    val sseUrl: String,
)

@PrimaryAdapter
@RestController
@RequestMapping("/api/bpmn")
@Profile("web")
internal class BpmnWebController(
    private val generationStarter: WebGenerationStarter,
    private val agentPlatform: AgentPlatform,
) {
    @PostMapping("/generations")
    fun startGeneration(
        @Valid @RequestBody request: WebGenerationRequest,
    ): ResponseEntity<WebGenerationResponse> {
        val processId = generationStarter.start(request)
        return ResponseEntity.accepted().body(
            WebGenerationResponse(
                processId = processId,
                sseUrl = "events/process/$processId",
            ),
        )
    }

    /**
     * Serves the terminal [BpmnResult.xml] for a finished generation as an `application/xml`
     * attachment (ADR-ss-004: reads the Embabel process store; no bpmner-side registry).
     *
     * - `200`: run completed with XML — body is byte-identical to the final `BpmnResult.xml`.
     * - `404`: unknown process id (or process evicted from the in-memory store).
     * - `409`: process is still running, or finished without producing XML (budget-exhausted /
     *   stuck / NEEDS_CLARIFICATION terminal with no XML).
     */
    @GetMapping("/generations/{id}/bpmn", produces = [MediaType.APPLICATION_XML_VALUE])
    fun downloadBpmn(@PathVariable id: String): ResponseEntity<String> {
        val process =
            try {
                agentPlatform.getAgentProcess(id)
            } catch (_: Exception) {
                null
            } ?: return ResponseEntity.notFound().build()

        val xml =
            process.last(BpmnResult::class.java)?.xml
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("$id.bpmn").build().toString(),
            )
            .body(xml)
    }
}
