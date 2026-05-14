package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnXmlParser {
    fun parse(xml: String): BpmnDefinition
}
