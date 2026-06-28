/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.outbound

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessBudgetConfig
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnReadinessInvoker(
    private val agentPlatform: AgentPlatform,
    private val config: BpmnReadinessBudgetConfig,
    private val listeners: List<AgenticEventListener>,
) : BpmnReadinessInvoker {
    /**
     * Runs the readiness assessment as a sub-process scoped to **only** [BpmnReadinessAgent].
     *
     * A whole-platform plan for `ProcessInputAssessment` also matches the orchestrator's `assessReadiness`
     * action — which calls this invoker — so the readiness sub-process could re-select it and recurse
     * without bound. Binding the process to the single readiness agent removes that goal collision.
     */
    override fun assess(request: BpmnRequest): ProcessInputAssessment {
        val agent = agentPlatform.agents().find { it.name == READINESS_AGENT_NAME }
            ?: error("Agent platform has no agent named '$READINESS_AGENT_NAME'")
        val process = agentPlatform.createAgentProcessFrom(
            agent,
            ProcessOptions(
                budget = Budget(actions = config.readiness),
                verbosity = TRACE_VERBOSITY,
                ephemeral = true,
                listeners = listeners,
            ),
            request,
        )
        process.run()
        return ProcessInputAssessment::class.java.cast(
            AgentProcessExecution.fromProcessStatus(request, process).output,
        )
    }

    private companion object {
        const val READINESS_AGENT_NAME = "BpmnReadinessAgent"
        val TRACE_VERBOSITY = Verbosity(
            showPrompts = true,
            showLlmResponses = true,
            debug = false,
            showPlanning = true,
        )
    }
}
