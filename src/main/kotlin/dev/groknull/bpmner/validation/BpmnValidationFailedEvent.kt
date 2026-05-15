package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnRequest
import org.jmolecules.event.annotation.DomainEvent

@DomainEvent
data class BpmnValidationFailedEvent(
    val request: BpmnRequest,
    val diagnostics: List<BpmnDiagnostic>,
    val attemptNumber: Int,
    val repairAttempts: Int,
    val processId: String? = null,
)
