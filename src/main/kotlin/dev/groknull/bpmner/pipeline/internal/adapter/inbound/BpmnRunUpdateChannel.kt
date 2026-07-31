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
 * The anti-corruption layer (ADR-605-04): the single Embabel [OutputChannel] registered on
 * every generation run's `ProcessOptions` (`AgentPlatformBpmnAgentInvoker`), plus the platform
 * lifecycle listener and bpmner `@DomainEvent` listeners that together translate Embabel
 * signals and bpmner's own deterministic milestones into the ordered [dev.groknull.bpmner.pipeline.RunUpdate]
 * stream held by [RunUpdateSinkRegistry].
 *
 * Imports `com.embabel.agent.api.channel.*` and `com.embabel.agent.api.event.*` only — the
 * public API side of Embabel's API/SPI line — never `com.embabel.agent.web.sse.*`. Emits only
 * [dev.groknull.bpmner.pipeline.RunUpdate] outward: no Embabel type, action name, prompt,
 * model reasoning, credential, or provider payload ever reaches a `RunUpdate` (Stage 1 exit
 * gate; `detail` below is always an explicitly built, flat `String -> String` map).
 *
 * A single `@Component` doubles as both seams deliberately: [OutputChannel] is injected
 * directly into `ProcessOptions` (per-run), while [AgenticEventListener] beans are
 * auto-registered globally on the platform (the same pattern the deleted
 * `BpmnResultEventPublisher` / `BpmnClarificationRequestEventPublisher` used) — one bean, one
 * registration each, so nothing fires twice.
 *
 * The bpmner `@DomainEvent` listeners below are plain, synchronous `@EventListener`s — **not**
 * `@ApplicationModuleListener`. Spring Modulith's `@ApplicationModuleListener` composes `@Async`
 * (verified against the framework source), and the project has no event-publication registry
 * (JDBC/JPA) that it requires anyway.
 *
 * They read `event.processId` — a field every one of the six milestone `@DomainEvent`s carries
 * — never [AgentProcess.get] themselves. Each producer captures `AgentProcess.get()?.id`
 * synchronously at publish time, inside its own `@Action` (see each event's KDoc); that is the
 * one point in the codebase guaranteed to be correct regardless of dispatch mode, because it
 * runs *before* any event-listener machinery — sync, `@Async`, or otherwise — gets involved.
 * Reading the ThreadLocal here in the listener instead would have two independent failure modes:
 * an `@Async` listener runs off the publishing thread entirely, and `BpmnReadinessAssessedEvent`
 * specifically is published from *inside* a separate, scoped Embabel sub-process
 * (`AgentPlatformBpmnReadinessInvoker`) — so even fully synchronous dispatch would resolve to
 * the wrong (child) process id for that one event. Consume-time resolution is never safe;
 * publish-time capture always is.
 */
@InfrastructureRing
@Component
internal class BpmnRunUpdateChannel(
    private val registry: RunUpdateSinkRegistry,
) : OutputChannel,
    AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnRunUpdateChannel::class.java)

    /** Round counter keyed by process id — mirrors the deleted `BpmnClarificationRequestEventPublisher`. */
    private val clarificationRounds = ConcurrentHashMap<String, Int>()

    // --- Embabel OutputChannel seam (registered via ProcessOptions.outputChannel, D1) ---

    override fun send(event: OutputChannelEvent) {
        // Only ProgressOutputChannelEvent is meaningful for Stage 1; no @Action in this
        // codebase sends one today, so this is presently dormant, but it is the seam a future
        // LLM-authored narration (Embabel's MessageOutputChannelEvent/`communicate` tool) or a
        // richer per-action progress tool would ride without any new port (ADR-605-04).
        if (event is ProgressOutputChannelEvent) {
            registry.emitNarration(event.processId, event.message)
        }
    }

    // --- Embabel lifecycle seam (auto-registered AgenticEventListener) ---

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

    // --- bpmner @DomainEvent milestones (deterministic domain sequence, ARCHITECTURE.md) ---

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

// A null processId here means the producer failed to capture AgentProcess.get() at publish
// time (see each event's KDoc) — a producer bug, not a legitimate runtime case, so it's logged
// rather than silently dropped. Kept as a top-level function (not a class member) purely to
// stay under detekt's TooManyFunctions threshold for BpmnRunUpdateChannel.
private fun requireProcessId(logger: Logger, processId: String?, source: String): String? {
    if (processId == null) {
        logger.warn("{} published with no processId; RunUpdate dropped.", source)
    }
    return processId
}
