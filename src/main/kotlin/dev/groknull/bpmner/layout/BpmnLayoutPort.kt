package dev.groknull.bpmner.layout

import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnLayoutPort {
    fun layout(xml: String): String
}
