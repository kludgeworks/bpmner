package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnRenderer {
    fun render(definition: BpmnDefinition): RenderedBpmn

    fun render(graph: LaidOutProcessGraph): RenderedBpmn
}
