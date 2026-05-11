package dev.groknull.bpmner.generation.internal.adapter.outbound

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnResult
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
}
