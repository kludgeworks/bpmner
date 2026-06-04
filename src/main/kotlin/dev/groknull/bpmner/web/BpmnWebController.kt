/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.web

import dev.groknull.bpmner.generation.BpmnResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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

data class WebGenerationBlockedResponse(
    val status: String,
    val reportFile: String?,
)

@RestController
@RequestMapping("/api/bpmn")
@Profile("web")
class BpmnWebController(
    private val generationStarter: WebGenerationStarter,
) {
    @PostMapping("/generations")
    fun startGeneration(
        @Valid @RequestBody request: WebGenerationRequest,
    ): ResponseEntity<Any> {
        return when (val outcome = generationStarter.start(request)) {
            is WebGenerationStartOutcome.Started -> {
                ResponseEntity.accepted().body(
                    WebGenerationResponse(
                        processId = outcome.processId,
                        sseUrl = "events/process/${outcome.processId}",
                    ),
                )
            }

            is WebGenerationStartOutcome.Blocked -> {
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(blockedBody(outcome.result))
            }
        }
    }

    private fun blockedBody(result: BpmnResult): WebGenerationBlockedResponse = WebGenerationBlockedResponse(
        status = result.status.name,
        reportFile = result.reportFile,
    )
}
