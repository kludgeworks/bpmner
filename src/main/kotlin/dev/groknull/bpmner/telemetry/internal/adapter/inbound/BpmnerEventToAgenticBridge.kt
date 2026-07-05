/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.web.sse.SSEController
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Bridges bpmner's custom SSE events onto Embabel's [SSEController].
 *
 * The telemetry publishers emit custom [AgentProcessEvent]s ([dev.groknull.bpmner.telemetry.BpmnStageEvent],
 * [dev.groknull.bpmner.telemetry.BpmnSnapshotEvent], [com.embabel.agent.api.event.ProgressUpdateEvent],
 * [dev.groknull.bpmner.telemetry.BpmnResultEvent], [dev.groknull.bpmner.telemetry.BpmnRunCostEvent],
 * [dev.groknull.bpmner.telemetry.BpmnClarificationRequestEvent]) on Spring's
 * `ApplicationEventPublisher`. Embabel's [SSEController] only receives events on its
 * `AgenticEventListener` bus and never sees raw Spring events, so without this bridge the browser
 * gets nothing.
 *
 * It forwards each event to the single [SSEController] sink — the only listener that needs these
 * custom events. An earlier version fanned out to `List<AgenticEventListener>`, which also picked up
 * Embabel's aggregate/multicast listener bean (that bean wraps the same leaf listeners), so every
 * event was delivered — and streamed to the browser — twice, surfacing as duplicated progress/cost
 * lines. Targeting the SSE sink directly keeps delivery exactly-once, and is a no-op when the web
 * layer is absent (CLI/shell runs), via [ObjectProvider.ifAvailable].
 */
@Component
class BpmnerEventToAgenticBridge(
    private val sseController: ObjectProvider<SSEController>,
) {
    @EventListener
    fun onSpringEvent(event: AgentProcessEvent) {
        sseController.ifAvailable { it.onProcessEvent(event) }
    }
}
