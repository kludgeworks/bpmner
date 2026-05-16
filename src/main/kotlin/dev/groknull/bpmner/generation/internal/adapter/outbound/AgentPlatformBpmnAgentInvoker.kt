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
