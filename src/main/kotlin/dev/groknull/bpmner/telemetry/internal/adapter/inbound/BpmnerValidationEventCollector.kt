/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class BpmnerCollectedValidationEvents(
    val failed: List<BpmnValidationFailedEvent> = emptyList(),
    val passed: BpmnValidationPassedEvent? = null,
)

@PrimaryAdapter
@Component
class BpmnerValidationEventCollector {
    private val clock: Clock = Clock.systemUTC()
    private val eventsByProcessId = ConcurrentHashMap<String, TimedValidationEvents>()

    @EventListener
    fun onValidationFailed(event: BpmnValidationFailedEvent) {
        val processId = event.processId ?: return
        pruneExpired()
        eventsByProcessId.compute(processId) { _, current ->
            val existing = current?.events ?: BpmnerCollectedValidationEvents()
            TimedValidationEvents(
                updatedAt = clock.instant(),
                events = existing.copy(failed = existing.failed + event),
            )
        }
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        val processId = event.processId ?: return
        pruneExpired()
        eventsByProcessId.compute(processId) { _, current ->
            val existing = current?.events ?: BpmnerCollectedValidationEvents()
            TimedValidationEvents(
                updatedAt = clock.instant(),
                events = existing.copy(passed = event),
            )
        }
    }

    fun removeFor(processId: String): BpmnerCollectedValidationEvents {
        pruneExpired()
        return eventsByProcessId.remove(processId)?.events ?: BpmnerCollectedValidationEvents()
    }

    private fun pruneExpired() {
        val cutoff = clock.instant().minus(EVENT_TTL)
        eventsByProcessId.entries.removeIf { it.value.updatedAt.isBefore(cutoff) }
    }

    private data class TimedValidationEvents(
        val updatedAt: Instant,
        val events: BpmnerCollectedValidationEvents,
    )

    companion object {
        private val EVENT_TTL: Duration = Duration.ofHours(1)
    }
}
