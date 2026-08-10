/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.authoring.BpmnGraphComposedEvent
import dev.groknull.bpmner.authoring.BpmnRunAbortedEvent
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import dev.groknull.bpmner.contract.BpmnContractExtractedEvent
import dev.groknull.bpmner.layout.BpmnLayoutCompletedEvent
import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.readiness.BpmnReadinessAssessedEvent
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Anti-corruption layer, bpmner-facing half: translates bpmner's own `@DomainEvent` milestones
 * into the ordered [dev.groknull.bpmner.pipeline.RunUpdate] stream in [RunUpdateSinkRegistry].
 * The Embabel-facing half, [BpmnRunUpdateChannel], is a separate `@Component` — this class
 * imports no Embabel type at all.
 *
 * Listeners are plain `@EventListener`s (not `@ApplicationModuleListener`, which needs an
 * event-publication registry this project doesn't configure) and read `event.processId`,
 * captured at publish time by each producing `@Action` — never `AgentProcess.get()` here (see
 * each event's KDoc). `detail` stays a flat, whitelisted `String -> String` bag; no Embabel
 * type, action name, prompt, credential, or provider payload.
 */
@InfrastructureRing
@Component
internal class BpmnMilestoneEventListener(
    private val registry: RunUpdateSinkRegistry,
) {
    private val logger = LoggerFactory.getLogger(BpmnMilestoneEventListener::class.java)

    @EventListener
    fun onReadinessAssessed(event: BpmnReadinessAssessedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnReadinessAssessedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.READINESS,
            artifactState = ArtifactState.NONE,
            summary = "Assessed input readiness (${event.assessment.verdict.name.lowercase()}).",
        )
    }

    @EventListener
    fun onContractExtracted(event: BpmnContractExtractedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnContractExtractedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.CONTRACT,
            artifactState = ArtifactState.NONE,
            summary = "Extracted the process contract.",
            detail = mapOf(
                "issueCount" to event.contract.report.issues.size.toString(),
            ),
        )
    }

    @EventListener
    fun onGraphComposed(event: BpmnGraphComposedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnGraphComposedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.OUTLINE,
            artifactState = ArtifactState.GRAPH_DRAFT,
            summary = "Composed the process graph structure.",
            detail = mapOf(
                "nodeCount" to event.graph.definition.nodes.size.toString(),
                "edgeCount" to event.graph.definition.sequences.size.toString(),
                "conformanceCorrections" to event.corrections.size.toString(),
            ),
        )
    }

    @EventListener
    fun onGenerated(event: BpmnGeneratedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnGeneratedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.DRAFT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Rendered a draft BPMN diagram.",
        )
    }

    @EventListener
    fun onValidationFailed(event: BpmnValidationFailedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnValidationFailedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.VALIDATION,
            artifactState = ArtifactState.DIAGNOSTIC,
            summary = "Validating and repairing (attempt ${event.attemptNumber}).",
            detail = mapOf(
                "attemptNumber" to event.attemptNumber.toString(),
                "graphIssues" to event.diagnostics.count { it.source == BpmnDiagnosticSource.GRAPH }.toString(),
                "xsdIssues" to event.diagnostics.count { it.source == BpmnDiagnosticSource.XSD }.toString(),
                "lintIssues" to event.diagnostics.count { it.source == BpmnDiagnosticSource.LINT }.toString(),
            ),
        )
    }

    @EventListener
    fun onValidationPassed(event: BpmnValidationPassedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnValidationPassedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.VALIDATION,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Validation passed after ${event.repairAttempts} repair attempt(s).",
        )
    }

    @EventListener
    fun onLayoutCompleted(event: BpmnLayoutCompletedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnLayoutCompletedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.LAYOUT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Applied automatic diagram layout.",
        )
    }

    // Backstop for a run that died without reaching any terminal of its own — see
    // BpmnRunAbortedEvent. Emits the terminal the failed run never produced; the registry drops
    // it if the run did manage to report one.
    @EventListener
    fun onRunAborted(event: BpmnRunAbortedEvent) {
        registry.emitTerminal(
            processId = event.processId,
            artifactState = ArtifactState.NONE,
            summary = "BPMN generation stopped unexpectedly.",
            outcome = RunOutcome.FAILED,
            detail = mapOf("failureDetail" to event.detail),
        )
    }

    @EventListener
    fun onAlignmentChecked(event: BpmnAlignmentCheckedEvent) {
        val processId = requireProcessId(logger, event.processId, "BpmnAlignmentCheckedEvent") ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.ALIGNMENT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Checked semantic alignment (${event.report.verdict.name.lowercase()}).",
        )
    }
}

/**
 * A null [processId] is a producer bug (see each event's KDoc), not a legitimate runtime case
 * — logged, not silently dropped.
 */
private fun requireProcessId(logger: Logger, processId: String?, source: String): String? {
    if (processId == null) {
        logger.warn("{} published with no processId; RunUpdate dropped.", source)
    }
    return processId
}
