/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.web

import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import dev.groknull.bpmner.generation.BpmnAgentInvoker
import org.springframework.stereotype.Service

@Service
class WebGenerationStarter(
    private val agentInvoker: BpmnAgentInvoker,
) {
    /**
     * Starts an async BPMN generation process and returns its process ID.
     *
     * The process runs in [GenerationMode.INTERACTIVE] so the agent's `assessReadiness`
     * `@State` machine can pause into a `WaitFor.formSubmission` when clarification is needed
     * and surface it over SSE — there is no synchronous 422 `Blocked` branch (architecture G6,
     * ADR-8 option b).
     */
    fun start(request: WebGenerationRequest): String {
        val bpmnRequest =
            BpmnRequest(
                processDescription = request.processDescription,
                styleGuide = request.styleGuide?.trim()?.takeIf { it.isNotEmpty() },
                outputFile = null,
                mode = GenerationMode.INTERACTIVE,
            )
        return agentInvoker.startAsync(bpmnRequest)
    }
}
