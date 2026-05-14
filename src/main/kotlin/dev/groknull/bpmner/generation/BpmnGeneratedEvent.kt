package dev.groknull.bpmner.generation
import dev.groknull.bpmner.core.BpmnRequest


import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnGeneratedEvent(
    val request: BpmnRequest,
    val rendered: RenderedBpmn,
)
