package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnReadinessInvoker {
    fun assess(request: BpmnRequest): ProcessInputAssessment
}
