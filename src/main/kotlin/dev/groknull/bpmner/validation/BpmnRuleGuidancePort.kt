package dev.groknull.bpmner.validation

import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnRuleGuidancePort {
    fun getLlmRuleGuidance(): String
}
