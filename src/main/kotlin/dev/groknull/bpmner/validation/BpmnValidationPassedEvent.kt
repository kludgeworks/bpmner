package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnRequest

data class BpmnValidationPassedEvent(
    val request: BpmnRequest,
    val xml: String,
    val repairAttempts: Int,
)
