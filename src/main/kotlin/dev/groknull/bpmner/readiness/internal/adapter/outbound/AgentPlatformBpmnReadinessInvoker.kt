/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.outbound

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
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
            // No listeners here: AgenticEventListener @Components are already auto-registered
            // globally on the platform; passing them again would double every event delivery.
            ProcessOptions(
                budget = Budget(actions = config.readiness),
                ephemeral = true,
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
    }
}
