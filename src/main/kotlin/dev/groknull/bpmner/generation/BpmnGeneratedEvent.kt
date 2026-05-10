package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.RenderedBpmn

data class BpmnGeneratedEvent(
    val request: BpmnRequest,
    val rendered: RenderedBpmn,
)
