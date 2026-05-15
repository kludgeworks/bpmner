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
