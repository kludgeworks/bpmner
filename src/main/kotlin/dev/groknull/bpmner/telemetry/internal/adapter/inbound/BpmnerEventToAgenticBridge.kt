/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Bridges Spring [AgentProcessEvent]s back into Embabel's [AgenticEventListener]s.
 * Embabel's SSEController only receives events emitted by the agent engine to [AgenticEventListener]s.
 * Bpmner custom events (BpmnStageEvent, BpmnSnapshotEvent, etc.) are published via Spring's
 * ApplicationEventPublisher, so they must be forwarded here to reach the SSE stream.
 */
@Component
class BpmnerEventToAgenticBridge(
    private val agenticEventListeners: List<AgenticEventListener>
) {
    @EventListener
    fun onSpringEvent(event: AgentProcessEvent) {
        agenticEventListeners.forEach { it.onProcessEvent(event) }
    }
}
