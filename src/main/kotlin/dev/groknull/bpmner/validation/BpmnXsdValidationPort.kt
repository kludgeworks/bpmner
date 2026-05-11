package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
