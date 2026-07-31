/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.channel.OutputChannelEvent
import com.embabel.agent.api.channel.ProgressOutputChannelEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.ux.form.RadioGroup
import dev.groknull.bpmner.alignment.BpmnAlignmentCheckedEvent
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import dev.groknull.bpmner.conformance.format
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-corruption layer: the Embabel [OutputChannel] registered on every run's `ProcessOptions`,
 * plus the platform lifecycle listener and bpmner `@DomainEvent` listeners, translating Embabel
 * signals and bpmner's own milestones into the ordered [dev.groknull.bpmner.pipeline.RunUpdate]
 * stream held by [RunUpdateSinkRegistry].
 *
 * Imports `com.embabel.agent.api.channel.*`/`api.event.*` only, never `web.sse.*`, and emits only
 * [dev.groknull.bpmner.pipeline.RunUpdate] outward — no Embabel type, action name, prompt,
 * credential, or provider payload in `detail`.
 *
 * The `@DomainEvent` listeners are plain `@EventListener`s, not `@ApplicationModuleListener`
 * (which requires an event-publication registry this project doesn't configure), and read
 * `event.processId` rather than [AgentProcess.get]: publish-time capture inside each producing
 * `@Action` is the one point guaranteed correct regardless of dispatch mode or sub-process
 * scoping (see each event's KDoc).
 */
@InfrastructureRing
@Component
internal class BpmnRunUpdateChannel(
    private val registry: RunUpdateSinkRegistry,
) : OutputChannel,
    AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnRunUpdateChannel::class.java)

    /** Clarification round counter keyed by process id, for the `AWAITING_INPUT` detail bag. */
    private val clarificationRounds = ConcurrentHashMap<String, Int>()

    override fun send(event: OutputChannelEvent) {
        // Only ProgressOutputChannelEvent is meaningful here; no @Action in this codebase sends
        // one today, so this is presently dormant, but it is the seam any future LLM-authored
        // narration (Embabel's MessageOutputChannelEvent/`communicate` tool) or a richer
        // per-action progress tool would ride without any new port.
        if (event is ProgressOutputChannelEvent) {
            registry.emitNarration(event.processId, event.message)
        }
    }

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is AgentProcessWaitingEvent -> onWaiting(event.agentProcess)
            // AgentProcessFailedEvent extends AgentProcessFinishedEvent; listed first so it
            // wins this `when` before the broader FinishedEvent branch.
            is AgentProcessFailedEvent -> onFailed(event.agentProcess)
            is AgentProcessFinishedEvent -> onFinished(event.agentProcess)
            else -> {}
        }
    }

    private fun onWaiting(process: AgentProcess) {
        val form = process.last(FormBindingRequest::class.java) ?: return
        val round = clarificationRounds.merge(process.id, 1, Int::plus)!!
        val options = form.payload.controls
            .filterIsInstance<RadioGroup>()
            .flatMap { control -> control.options.map { it.value } }
        registry.emit(
            processId = process.id,
            phase = RunPhase.AWAITING_INPUT,
            artifactState = ArtifactState.NONE,
            summary = form.payload.title,
            detail = buildMap {
                put("round", round.toString())
                put("maxRounds", MAX_CLARIFICATION_ROUNDS.toString())
                if (options.isNotEmpty()) put("options", options.joinToString(OPTION_SEPARATOR))
            },
        )
    }

    private fun onFailed(process: AgentProcess) {
        clarificationRounds.remove(process.id)
        registry.emitTerminal(
            processId = process.id,
            artifactState = ArtifactState.NONE,
            summary = "BPMN generation failed to complete.",
            outcome = RunOutcome.FAILED,
        )
    }

    private fun onFinished(process: AgentProcess) {
        clarificationRounds.remove(process.id)
        val result = process.last(BpmnResult::class.java)
        if (result == null) {
            registry.emitTerminal(
                processId = process.id,
                artifactState = ArtifactState.NONE,
                summary = "BPMN generation did not complete.",
                outcome = RunOutcome.FAILED,
            )
            return
        }
        registry.emitTerminal(
            processId = process.id,
            artifactState = artifactStateFor(result.status),
            summary = summaryFor(result.status),
            outcome = if (result.status == BpmnGenerationStatus.GENERATED) RunOutcome.COMPLETED else RunOutcome.FAILED,
            detail = buildMap {
                put("status", result.status.name)
                result.alignmentReport?.verdict?.name?.let { put("alignmentVerdict", it) }
                result.alignmentReport?.rationale?.let { put("alignmentReport", it) }
                result.validationDiagnostics
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { diagnostics -> put("diagnostics", diagnostics.joinToString("\n") { it.format() }) }
            },
        )
    }

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

    private companion object {
        // Mirrors BpmnGenerationAgent.MAX_ROUNDS (private const = 3).
        private const val MAX_CLARIFICATION_ROUNDS = 3
        private const val OPTION_SEPARATOR = "|"
    }
}

private fun artifactStateFor(status: BpmnGenerationStatus): ArtifactState = when (status) {
    BpmnGenerationStatus.GENERATED, BpmnGenerationStatus.ALIGNMENT_FAILED -> ArtifactState.FINAL
    BpmnGenerationStatus.VALIDATION_FAILED -> ArtifactState.DIAGNOSTIC
    BpmnGenerationStatus.NEEDS_CLARIFICATION -> ArtifactState.NONE
}

private fun summaryFor(status: BpmnGenerationStatus): String = when (status) {
    BpmnGenerationStatus.GENERATED -> "BPMN generation complete."
    BpmnGenerationStatus.NEEDS_CLARIFICATION -> "Needs clarification — generation stopped."
    BpmnGenerationStatus.ALIGNMENT_FAILED -> "Alignment failed — reviewing the generated BPMN."
    BpmnGenerationStatus.VALIDATION_FAILED -> "Validation failed — generation stopped."
}

// A null processId is a producer bug (see each event's KDoc), not a legitimate runtime case —
// logged, not silently dropped.
private fun requireProcessId(logger: Logger, processId: String?, source: String): String? {
    if (processId == null) {
        logger.warn("{} published with no processId; RunUpdate dropped.", source)
    }
    return processId
}
