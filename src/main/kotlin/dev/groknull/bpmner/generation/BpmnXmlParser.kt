package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnXmlParser {
    fun parse(xml: String): BpmnDefinition
}
