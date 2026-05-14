package dev.groknull.bpmner.generation
import dev.groknull.bpmner.layout.LaidOutProcessGraph

import dev.groknull.bpmner.core.BpmnDefinition


import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnRenderer {
    fun render(definition: BpmnDefinition): RenderedBpmn

    fun render(graph: LaidOutProcessGraph): RenderedBpmn
}
