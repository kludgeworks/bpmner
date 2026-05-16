package dev.groknull.bpmner.observability.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.GlobalDiagnostics
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.generation.BpmnGeneratedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnPipelineObserver(
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnPipelineObserver::class.java)

    private val currentProcess: AgentProcess?
        get() = try {
            AgentProcess.get()
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") e: Exception) {
            null
        }

    @EventListener
    fun onBpmnGenerated(event: BpmnGeneratedEvent) {
        val process = currentProcess ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "INITIAL_RENDER",
                xml = event.rendered.xml,
            )
        )
    }

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

        val process = currentProcess ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "VALIDATION_FAILED",
                attemptNumber = event.attemptNumber,
                xml = event.xml,
                diagnostics = event.diagnostics,
            )
        )
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        logger.info(
            "Validation passed after {} repair attempt(s), xmlLength={}",
            event.repairAttempts,
            event.xml.length,
        )

        val process = currentProcess ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "FINAL_VALIDATION",
                attemptNumber = event.repairAttempts,
                xml = event.xml,
            )
        )
    }
}
