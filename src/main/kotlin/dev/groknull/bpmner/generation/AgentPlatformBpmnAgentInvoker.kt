/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
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
                syncGenerationProcessOptions(),
                request,
                assessment,
            )
        process.run()
        // Phase 4 (#219): `fromProcessStatus()` returns the goal output on COMPLETED and throws
        // the framework's typed status exceptions (`ProcessExecutionStuckException` when the
        // planner has no applicable action, `ProcessExecutionTerminatedException` on budget
        // exhaustion). Replaces the prior `process.resultOfType()` which crashed silently on
        // non-COMPLETED states and the bespoke `BpmnRefinementFailureException`.
        val execution = AgentProcessExecution.fromProcessStatus(request, process)
        return resultClass.cast(execution.output)
    }

    override fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATE_BPMN_GOAL_NAME }
                ?: error("Agent platform has no agent exporting goal '$GENERATE_BPMN_GOAL_NAME'")
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

    // Seed both request and readiness assessment; AgentPlatformTypedOps supports one input binding.
    private fun synthesizeResultAgent(resultClass: Class<*>): Agent = agentPlatform
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

        private fun syncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
            budget = Budget(actions = 100),
            ephemeral = true,
        )

        private fun asyncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
            budget = Budget(actions = 100),
        )
    }
}
