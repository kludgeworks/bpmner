/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnAgentInvoker {
    fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult

    fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String

    /**
     * Web-path overload: starts the async generation process without a pre-computed assessment.
     * The agent's own assessReadiness action (the @State machine) runs inside the process,
     * surfacing clarification as an in-process WaitFor form over SSE — never a 422.
     *
     * The sync [generate] path is a separate seam (CLI/shell) and is left intact (architecture
     * §8 risk 2): do not remove or alter that overload.
     */
    fun startAsync(
        request: BpmnRequest,
    ): String
}

@org.springframework.stereotype.Component
internal class BpmnAgentLauncher(
    private val agentInvoker: BpmnAgentInvoker,
) {
    fun startAsync(request: BpmnRequest): String {
        return agentInvoker.startAsync(request)
    }
}
