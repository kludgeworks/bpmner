package dev.groknull.bpmner.repair.internal.adapter.inbound

import dev.groknull.bpmner.generation.BpmnGeneratedEvent
import dev.groknull.bpmner.repair.internal.domain.BpmnRefinementEngine
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
internal class BpmnRepairEventListener(
    @Suppress("UnusedPrivateProperty") // placeholder for future background-refinement integration
    private val refinementEngine: BpmnRefinementEngine,
) {
    @EventListener
    @Suppress("UnusedParameter") // Spring dispatches by parameter type
    fun onBpmnGenerated(event: BpmnGeneratedEvent) {
        // This listener could trigger background refinement if needed,
        // but currently the orchestration is handled by Embabel agent chaining.
    }
}
