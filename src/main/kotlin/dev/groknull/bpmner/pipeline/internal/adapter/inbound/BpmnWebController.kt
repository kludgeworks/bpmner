/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.hitl.FormBindingRequest
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.pipeline.RunUpdate
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
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
import reactor.core.publisher.Flux

data class WebGenerationRequest(
    @field:NotBlank
    @field:Size(max = MAX_DESCRIPTION_LENGTH)
    val processDescription: String,
    @field:Size(max = MAX_STYLE_GUIDE_LENGTH)
    val styleGuide: String? = null,
    val targetLanguage: String? = null,
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

@InfrastructureRing
@RestController
@RequestMapping("/api/bpmn")
@Profile("web")
internal class BpmnWebController(
    private val generationStarter: WebGenerationStarter,
    private val agentPlatform: AgentPlatform,
    private val runUpdates: RunUpdateSinkRegistry,
) {
    @PostMapping("/generations")
    fun startGeneration(
        @Valid @RequestBody request: WebGenerationRequest,
    ): ResponseEntity<WebGenerationResponse> {
        val processId = generationStarter.start(request)
        return ResponseEntity.accepted().body(
            WebGenerationResponse(
                processId = processId,
                sseUrl = "api/bpmn/generations/$processId/updates",
            ),
        )
    }

    /**
     * Native Spring reactive SSE endpoint for the ordered [RunUpdate] stream: bpmner owns this
     * delivery path outright — no reach into Embabel's `web.sse.SSEController`. Subscribes the
     * bounded per-process replay sink ([RunUpdateSinkRegistry]) so a late subscriber (the browser
     * connects only after the 202 response from [startGeneration]) still receives every prior
     * update before the live tail. Spring MVC natively streams a returned `Flux<T>` as
     * `text/event-stream` — no `SseEmitter` bridging code is needed.
     *
     * - `200`: known process id — streams its replay-then-live [RunUpdate] sequence.
     * - `404`: unknown process id, so no sink is ever created for it — without this check the
     *   registry would let an arbitrary caller grow unbounded by subscribing to made-up ids.
     */
    @GetMapping("/generations/{id}/updates", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun updates(@PathVariable id: String): ResponseEntity<Flux<RunUpdate>> {
        if (!agentProcessExists(id)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(runUpdates.subscribe(id))
    }

    private fun agentProcessExists(id: String): Boolean = try {
        agentPlatform.getAgentProcess(id) != null
    } catch (_: Exception) {
        false
    }

    /**
     * Serves the terminal [BpmnResult.xml] for a finished generation as an `application/xml`
     * attachment.
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

    /**
     * Accepts a free-text clarification answer, binds it to the parked process, and resumes it
     * asynchronously.
     *
     * - `202`: answer accepted; the run resumes over the existing SSE stream.
     * - `404`: unknown process id (or process evicted from the in-memory store).
     * - `409`: process is not in the `WAITING` state, or no `FormBindingRequest` is on the blackboard.
     * - `400`: `answers` field is blank (`@NotBlank` on [BpmnClarificationAnswers]).
     *
     * Uses `agentPlatform.start(process)` (async, returns `CompletableFuture`) rather than
     * `process.run()` (sync) so the POST returns 202 immediately while progress streams over SSE.
     */
    @PostMapping("/generations/{id}/answers")
    fun submitAnswers(
        @PathVariable id: String,
        @Valid @RequestBody answers: BpmnClarificationAnswers,
    ): ResponseEntity<Void> {
        val process =
            try {
                agentPlatform.getAgentProcess(id)
            } catch (_: Exception) {
                null
            } ?: return ResponseEntity.notFound().build()

        if (process.status != AgentProcessStatusCode.WAITING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build()
        }

        // Safe: BpmnGenerationAgent.clarificationFormFrom is this blackboard slot's sole
        // producer, always as FormBindingRequest<BpmnClarificationAnswers>.
        @Suppress("UNCHECKED_CAST")
        val form =
            process.last(FormBindingRequest::class.java) as? FormBindingRequest<BpmnClarificationAnswers>
                ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()

        form.bind(answers, process)
        agentPlatform.start(process)
        return ResponseEntity.accepted().build()
    }
}
