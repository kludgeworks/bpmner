package dev.groknull.bpmner.validation

import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
