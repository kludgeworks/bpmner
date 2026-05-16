/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.web

import dev.groknull.bpmner.generation.BpmnGenerationInput
import dev.groknull.bpmner.generation.BpmnGenerationUseCase
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.StartGenerationOutcome
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
    private val generationUseCase: BpmnGenerationUseCase,
) {
    @PostMapping("/generations")
    fun startGeneration(
        @Valid @RequestBody request: WebGenerationRequest,
    ): ResponseEntity<Any> {
        val input =
            BpmnGenerationInput(
                processDescription = request.processDescription,
                styleGuideContent = request.styleGuide,
            )

        return when (val outcome = generationUseCase.startAsync(input)) {
            is StartGenerationOutcome.Started -> {
                ResponseEntity.accepted().body(
                    WebGenerationResponse(
                        processId = outcome.processId,
                        sseUrl = "events/process/${outcome.processId}",
                    ),
                )
            }

            is StartGenerationOutcome.Blocked -> {
                ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(blockedBody(outcome.result))
            }
        }
    }

    private fun blockedBody(result: BpmnResult): WebGenerationBlockedResponse =
        WebGenerationBlockedResponse(
            status = result.status.name,
            reportFile = result.reportFile,
        )
}
