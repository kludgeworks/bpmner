package dev.groknull.bpmner.observability.internal.adapter.inbound

import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.GlobalDiagnostics
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnPipelineObserver {
    private val logger = LoggerFactory.getLogger(BpmnPipelineObserver::class.java)

    @EventListener
    fun onReadinessAssessed(event: BpmnReadinessAssessedEvent) {
        logger.info(
            "Readiness assessed: verdict={}, score={}, missingAreas={}, questions={}",
            event.assessment.verdict,
            event.assessment.overallScore,
            event.assessment.missingAreas.size,
            event.assessment.clarificationQuestions.size,
        )
    }

    @EventListener
    fun onAlignmentChecked(event: BpmnAlignmentCheckedEvent) {
        val unsupportedCount = event.report.alignedElements.count { it.classification == AlignmentClassification.UNSUPPORTED }
        val missingCount = event.report.alignedElements.count { it.classification == AlignmentClassification.MISSING }
        val assumptionCount = event.report.alignedElements.count { it.classification == AlignmentClassification.ASSUMED }

        logger.info(
            "Alignment checked: verdict={}, unsupported={}, missing={}, assumptions={}",
            event.report.verdict,
            unsupportedCount,
            missingCount,
            assumptionCount,
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
