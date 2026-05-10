package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.XsdValidationIssue

interface BpmnXsdValidationPort {
    fun validateDetailed(bpmnXml: String): List<XsdValidationIssue>
}
