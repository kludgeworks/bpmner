/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * On run completion, publishes a [BpmnRunCostEvent] carrying the framework's
 * [com.embabel.agent.core.LlmInvocationHistory.costInfoString] summary so the web client can display
 * the run cost. As an [AgenticEventListener] `@Component` it is auto-collected into the agent
 * invoker's `ProcessOptions.listeners` (like `BpmnerRunSummaryListener`), so it fires for every run.
 */
@PrimaryAdapter
@Component
class BpmnRunCostEventPublisher(
    private val eventPublisher: ApplicationEventPublisher,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val p = event.agentProcess
        eventPublisher.publishEvent(BpmnRunCostEvent(p, p.costInfoString(verbose = true)))
    }
}
