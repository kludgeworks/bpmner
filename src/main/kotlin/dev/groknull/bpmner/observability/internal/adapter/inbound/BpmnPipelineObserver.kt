package dev.groknull.bpmner.observability.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.GlobalDiagnostics
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnPipelineObserver {
    private val logger = LoggerFactory.getLogger(BpmnPipelineObserver::class.java)

    @EventListener
    fun onValidationFailed(event: BpmnValidationFailedEvent) {
        val global = GlobalDiagnostics(event.diagnostics)
        logger.info(
            "Validation failed on attempt {}: graph={}, xsd={}, lint={}, repairAttempts={}",
            event.attemptNumber,
            global.countFor(BpmnDiagnosticSource.GRAPH),
            global.countFor(BpmnDiagnosticSource.XSD),
            global.countFor(BpmnDiagnosticSource.LINT),
            event.repairAttempts,
        )
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        logger.info(
            "Validation passed after {} repair attempt(s), xmlLength={}",
            event.repairAttempts,
            event.xml.length,
        )
    }
}
