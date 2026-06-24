/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringBudgetConfig
import dev.groknull.bpmner.authoring.internal.domain.BpmnAgentInvoker
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnAgentInvoker(
    private val agentPlatform: AgentPlatform,
    private val config: BpmnAuthoringBudgetConfig,
    // Spring auto-collects every AgenticEventListener bean into this list. Empty if no
    // listener is registered — the framework treats an empty list as "no observers."
    private val listeners: List<AgenticEventListener>,
) : BpmnAgentInvoker {
    override fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                syncGenerationProcessOptions(),
                request,
                assessment,
            )
        process.run()
        // The helper `fromProcessStatus()` returns the goal output on COMPLETED and throws
        // the framework's typed status exceptions (`ProcessExecutionStuckException` when the
        // planner has no applicable action, `ProcessExecutionTerminatedException` on budget
        // exhaustion). This replaces any usage of `process.resultOfType()` which would crash
        // silently on non-COMPLETED states.
        //
        // This does not use `AgentPlatformTypedOps.transform()` because that path uses
        // the older `process.resultOfType()` and would lose the typed exception surface above.
        val execution = AgentProcessExecution.fromProcessStatus(request, process)
        return BpmnResult::class.java.cast(execution.output)
    }

    override fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                asyncGenerationProcessOptions(),
                request,
                assessment,
            )
        agentPlatform.start(process)
        return process.id
    }

    /**
     * Web-path overload: seeds only the [BpmnRequest]; the agent's `assessReadiness` action
     * (the `@State` machine) runs inside the process. Clarification surfaces as an in-process
     * `WaitFor` form over SSE — never a pre-computed 422 outcome.
     */
    override fun startAsync(request: BpmnRequest): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                asyncGenerationProcessOptions(),
                request,
            )
        agentPlatform.start(process)
        return process.id
    }

    // Sync CLI generation: blocks for a typed BpmnResult. `ephemeral = true` because the process
    // is short-lived and never queried for status.
    private fun syncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
        budget = Budget(actions = config.generation),
        ephemeral = true,
        listeners = listeners,
    )

    // Async web generation: returns the process id immediately; callers poll for status, so the
    // process must be persisted — `ephemeral = false`.
    private fun asyncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
        budget = Budget(actions = config.generation),
        ephemeral = false,
        listeners = listeners,
    )

    companion object {
        private const val GENERATION_AGENT_NAME = "BpmnGenerationAgent"
    }
}
