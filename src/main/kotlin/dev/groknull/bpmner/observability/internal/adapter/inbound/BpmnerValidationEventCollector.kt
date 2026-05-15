package dev.groknull.bpmner.observability.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

data class BpmnerCollectedValidationEvents(
    val failed: List<BpmnValidationFailedEvent> = emptyList(),
    val passed: BpmnValidationPassedEvent? = null,
)

@PrimaryAdapter
@Component
class BpmnerValidationEventCollector {
    private val eventsByRequest = ConcurrentHashMap<BpmnRequest, BpmnerCollectedValidationEvents>()

    @EventListener
    fun onValidationFailed(event: BpmnValidationFailedEvent) {
        eventsByRequest.compute(event.request) { _, current ->
            val existing = current ?: BpmnerCollectedValidationEvents()
            existing.copy(failed = existing.failed + event)
        }
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        eventsByRequest.compute(event.request) { _, current ->
            val existing = current ?: BpmnerCollectedValidationEvents()
            existing.copy(passed = event)
        }
    }

    fun removeFor(request: BpmnRequest?): BpmnerCollectedValidationEvents =
        if (request == null) {
            BpmnerCollectedValidationEvents()
        } else {
            eventsByRequest.remove(request) ?: BpmnerCollectedValidationEvents()
        }
}
