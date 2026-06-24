/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.GenerationMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
internal class WebGenerationStarter(
    private val agentPlatform: AgentPlatform,
    private val listeners: List<AgenticEventListener>,
    @Value("\${bpmner.budget.generation:100}") private val generationBudget: Int,
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
        val agent =
            agentPlatform.agents().find { it.name == "BpmnGenerationAgent" }
                ?: error("Agent platform has no agent named 'BpmnGenerationAgent'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                ProcessOptions(
                    budget = Budget(actions = generationBudget),
                    ephemeral = false,
                    listeners = listeners,
                ),
                bpmnRequest,
            )
        agentPlatform.start(process)
        return process.id
    }
}
