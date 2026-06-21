/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.alignment.AlignmentClassification
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnPipelineObserver(
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnPipelineObserver::class.java)

    @EventListener
    fun onBpmnGenerated(event: BpmnGeneratedEvent) {
        val process = currentProcessOrWarn("BpmnGeneratedEvent") ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "INITIAL_RENDER",
                xml = event.rendered.xml,
            ),
        )
    }

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
        val unsupportedCount = event.report.issues.count { it.classification == AlignmentClassification.UNSUPPORTED }
        val missingCount = event.report.issues.count { it.classification == AlignmentClassification.MISSING }
        val assumptionCount = event.report.issues.count { it.classification == AlignmentClassification.ASSUMED }

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

        val process = currentProcessOrWarn("BpmnValidationFailedEvent") ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "VALIDATION_FAILED",
                attemptNumber = event.attemptNumber,
                xml = event.xml,
                diagnostics = event.diagnostics,
            ),
        )
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        logger.info(
            "Validation passed after {} repair attempt(s), xmlLength={}",
            event.repairAttempts,
            event.xml.length,
        )

        val process = currentProcessOrWarn("BpmnValidationPassedEvent") ?: return
        eventPublisher.publishEvent(
            BpmnSnapshotEvent(
                process = process,
                stage = "FINAL_VALIDATION",
                attemptNumber = event.repairAttempts,
                xml = event.xml,
            ),
        )
    }

    // Snapshot publication depends on AgentProcess.get(), which is a ThreadLocal bound by the
    // agent runtime to the thread executing the action. Spring's default @EventListener is
    // synchronous so the listener fires on that same thread and the lookup resolves. If listeners
    // are ever marked @Async the ThreadLocal will be empty and snapshots will be dropped silently;
    // logging at warn makes that condition visible instead.
    private fun currentProcessOrWarn(source: String): AgentProcess? {
        val process = AgentProcess.get()
        if (process == null) {
            logger.warn("No AgentProcess bound to current thread while handling {}; snapshot dropped.", source)
        }
        return process
    }
}
