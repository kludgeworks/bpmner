/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Goal
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.common.Constants
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.internal.domain.BpmnAgentInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnAgentInvoker(
    private val agentPlatform: AgentPlatform,
) : BpmnAgentInvoker {
    override fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult {
        val resultClass = BpmnResult::class.java
        val goalAgent = synthesizeResultAgent(resultClass)
        val process =
            agentPlatform.createAgentProcessFrom(
                goalAgent,
                ProcessOptions(),
                request,
                assessment,
            )
        process.run()
        return process.resultOfType(resultClass)
    }

    override fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATE_BPMN_GOAL_NAME }
                ?: error("Agent platform has no agent exporting goal '$GENERATE_BPMN_GOAL_NAME'")
        val process = agentPlatform.createAgentProcessFrom(agent, ProcessOptions(), request, assessment)
        agentPlatform.start(process)
        return process.id
    }

    // Mirrors AgentPlatformTypedOps.transform: spin up a synthetic goal agent over the platform
    // scope so the planner can draw on every deployed action when planning toward BpmnResult.
    private fun synthesizeResultAgent(resultClass: Class<*>): Agent =
        agentPlatform
            .createAgent(
                name = "goal-${resultClass.simpleName}",
                provider = Constants.EMBABEL_PROVIDER,
                description = "Goal agent for ${resultClass.simpleName}",
            ).withSingleGoal(
                Goal(
                    name = "create-${resultClass.simpleName}",
                    description = "Create ${resultClass.simpleName}",
                    satisfiedBy = resultClass,
                ),
            )

    companion object {
        private const val GENERATE_BPMN_GOAL_NAME = "generateBpmn"
    }
}
