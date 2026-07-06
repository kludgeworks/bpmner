/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import org.springframework.stereotype.Service

@Service
internal class WebGenerationStarter(
    private val processGenerator: BpmnProcessGenerator,
) {
    /**
     * Starts an async BPMN generation process and returns its process ID.
     *
     * The process runs in [GenerationMode.INTERACTIVE] so the agent's `assessReadiness`
     * `@State` machine can pause into a `WaitFor.formSubmission` when clarification is needed
     * and surface it over SSE — there is no synchronous 422 `Blocked` branch (architecture G6,
     * ADR-003 option b).
     */
    fun start(request: WebGenerationRequest): String {
        val bpmnRequest =
            BpmnRequest(
                processDescription = request.processDescription,
                styleGuide = request.styleGuide?.trim()?.takeIf { it.isNotEmpty() },
                outputFile = null,
                mode = GenerationMode.INTERACTIVE,
            )
        return processGenerator.startAsync(bpmnRequest)
    }
}
