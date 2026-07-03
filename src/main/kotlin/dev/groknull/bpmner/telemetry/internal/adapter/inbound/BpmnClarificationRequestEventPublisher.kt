/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.hitl.FormBindingRequest
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes the Embabel `AgentProcessWaitingEvent` and publishes a [BpmnClarificationRequestEvent]
 * so the web client can display the current-round question and prompt the user for an answer
 * (ARCHITECTURE.md §ss-4, ADR-ss-003).
 *
 * Tracks the per-process round counter in a [ConcurrentHashMap] (incremented on each
 * [AgentProcessWaitingEvent], removed on terminal events) — deterministic across parallel async
 * runs and independent of blackboard internals. `MAX_ROUNDS = 3` mirrors `BpmnGenerationAgent.MAX_ROUNDS`
 * (private const; hard-coded here with a reference comment rather than a new shared constant).
 *
 * Observes the framework `AgentProcessWaitingEvent`; no agent modification (ADR-ss-003).
 * Mirrors [BpmnResultEventPublisher] structure exactly.
 */
@PrimaryAdapter
@Component
class BpmnClarificationRequestEventPublisher(
    private val eventPublisher: ApplicationEventPublisher,
) : AgenticEventListener {

    /** Round counter keyed by process id. Removed on terminal events to reset for any re-run. */
    private val rounds = ConcurrentHashMap<String, Int>()

    /**
     * Maximum clarification rounds — mirrors `BpmnGenerationAgent.MAX_ROUNDS` (private const = 3,
     * `pipeline/internal/adapter/inbound/BpmnGenerationAgent.kt:283`).
     */
    private companion object {
        private const val MAX_ROUNDS = 3
    }

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is AgentProcessWaitingEvent -> {
                val process = event.agentProcess
                val form = process.last(FormBindingRequest::class.java) ?: return
                val round = rounds.merge(process.id, 1, Int::plus)!!
                eventPublisher.publishEvent(
                    BpmnClarificationRequestEvent(
                        process = process,
                        round = round,
                        maxRounds = MAX_ROUNDS,
                        prompt = form.payload.title,
                    ),
                )
            }
            is AgentProcessFinishedEvent, is AgentProcessFailedEvent -> {
                rounds.remove(event.agentProcess.id)
            }
            else -> { /* no-op */ }
        }
    }
}
