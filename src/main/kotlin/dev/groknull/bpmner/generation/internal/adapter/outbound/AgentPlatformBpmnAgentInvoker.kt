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

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.internal.domain.BpmnAgentInvoker
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnAgentInvoker(
    private val agentPlatform: AgentPlatform,
) : BpmnAgentInvoker {
    override fun generate(request: BpmnRequest): BpmnResult =
        AgentPlatformTypedOps(agentPlatform)
            .transform<BpmnRequest, BpmnResult>(request, BpmnResult::class.java, ProcessOptions())

    override fun startAsync(request: BpmnRequest): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATE_BPMN_GOAL_NAME }
                ?: error("Agent platform has no agent exporting goal '$GENERATE_BPMN_GOAL_NAME'")
        val process = agentPlatform.createAgentProcess(agent, ProcessOptions(), mapOf("request" to request))
        agentPlatform.start(process)
        return process.id
    }

    companion object {
        private const val GENERATE_BPMN_GOAL_NAME = "generateBpmn"
    }
}
