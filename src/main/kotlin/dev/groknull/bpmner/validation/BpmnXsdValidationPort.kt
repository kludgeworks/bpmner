package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
