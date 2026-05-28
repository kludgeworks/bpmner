/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.outbound

import com.embabel.agent.api.common.AgentPlatformTypedOps
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal class AgentPlatformBpmnReadinessInvoker(
    private val agentPlatform: AgentPlatform,
    private val config: BpmnConfig,
    private val listeners: List<AgenticEventListener>,
) : BpmnReadinessInvoker {
    override fun assess(request: BpmnRequest): ProcessInputAssessment = AgentPlatformTypedOps(agentPlatform)
        .transform<BpmnRequest, ProcessInputAssessment>(
            request,
            ProcessInputAssessment::class.java,
            ProcessOptions(
                budget = Budget(actions = config.budget.readiness),
                ephemeral = true,
                listeners = listeners,
            ),
        )
}
