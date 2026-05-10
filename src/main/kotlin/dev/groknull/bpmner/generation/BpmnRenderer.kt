package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn

interface BpmnRenderer {
    fun render(graph: LaidOutProcessGraph): RenderedBpmn
    fun render(definition: BpmnDefinition): RenderedBpmn
}
