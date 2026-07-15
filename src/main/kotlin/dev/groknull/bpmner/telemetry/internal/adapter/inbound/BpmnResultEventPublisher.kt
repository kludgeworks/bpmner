/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.telemetry.BpmnResultEvent
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * On run completion, publishes a [BpmnResultEvent] carrying the terminal [BpmnResult] status,
 * alignment verdict, and alignment rationale so the web client can display a typed result bar
 * (ARCHITECTURE.md §ss-3, ADR-ss-004).
 *
 * As an [AgenticEventListener] `@Component` it is auto-registered globally on the platform — no
 * explicit wiring needed, and it must NOT also be added to `ProcessOptions.listeners` (same pattern
 * as [BpmnRunCostEventPublisher]), or it would fire twice per run.
 *
 * Guard: if no [BpmnResult] is present on the blackboard (budget-exhausted / stuck runs), no event
 * is published; the client falls back to the existing `AgentProcessFailedEvent`. Uses
 * [com.embabel.agent.core.Blackboard.last] (non-throwing, returns null when absent) rather than
 * [com.embabel.agent.core.AgentProcess.resultOfType], which crashes silently on non-COMPLETED states
 * (documented caution at `AgentPlatformBpmnAgentInvoker.kt:48`).
 */
@InfrastructureRing
@Component
class BpmnResultEventPublisher(
    private val eventPublisher: ApplicationEventPublisher,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val process = event.agentProcess
        val result = process.last(BpmnResult::class.java) ?: return
        eventPublisher.publishEvent(
            BpmnResultEvent(
                process = process,
                resultStatus = result.status.name,
                alignmentVerdict = result.alignmentReport?.verdict?.name,
                alignmentReport = result.alignmentReport?.rationale,
            ),
        )
    }
}
