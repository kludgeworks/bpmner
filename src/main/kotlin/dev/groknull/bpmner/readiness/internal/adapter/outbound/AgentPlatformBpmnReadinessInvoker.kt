package dev.groknull.bpmner.readiness.internal.adapter.outbound

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ProcessInputAssessment
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnReadinessInvoker(
    private val agentPlatform: AgentPlatform,
) : BpmnReadinessInvoker {
    override fun assess(request: BpmnRequest): ProcessInputAssessment =
        AgentPlatformTypedOps(agentPlatform)
            .transform<BpmnRequest, ProcessInputAssessment>(
                request,
                ProcessInputAssessment::class.java,
                ProcessOptions(),
            )
}
