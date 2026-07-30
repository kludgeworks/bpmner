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
 * `@ApplicationModuleListener`. Spring Modulith's `@ApplicationModuleListener` composes
 * `@Async` (verified against the framework source), which would run these handlers off the
 * action's thread; every one of them resolves the current run via [AgentProcess.get] (a
 * `ThreadLocal` bound only on the publishing thread — the same load-bearing constraint already
 * documented on the deleted `BpmnPipelineObserver`), and the project has no event-publication
 * registry (JDBC/JPA) that `@ApplicationModuleListener` requires. Async delivery here would
 * silently drop every milestone update.
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
        val process = currentProcessOrWarn("BpmnReadinessAssessedEvent") ?: return
        registry.emit(
            processId = process.id,
            phase = RunPhase.READINESS,
            artifactState = ArtifactState.NONE,
            summary = "Assessed input readiness (${event.assessment.verdict.name.lowercase()}).",
        )
    }

    @EventListener
    @Suppress("UnusedParameter") // the type is required for Spring's @EventListener dispatch
    fun onGenerated(event: BpmnGeneratedEvent) {
        val process = currentProcessOrWarn("BpmnGeneratedEvent") ?: return
        registry.emit(
            processId = process.id,
            phase = RunPhase.DRAFT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Rendered a draft BPMN diagram.",
        )
    }

    @EventListener
    fun onValidationFailed(event: BpmnValidationFailedEvent) {
        val processId = event.processId ?: currentProcessOrWarn("BpmnValidationFailedEvent")?.id ?: return
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
        val processId = event.processId ?: currentProcessOrWarn("BpmnValidationPassedEvent")?.id ?: return
        registry.emit(
            processId = processId,
            phase = RunPhase.VALIDATION,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Validation passed after ${event.repairAttempts} repair attempt(s).",
        )
    }

    @EventListener
    @Suppress("UnusedParameter") // the type is required for Spring's @EventListener dispatch
    fun onLayoutCompleted(event: BpmnLayoutCompletedEvent) {
        val process = currentProcessOrWarn("BpmnLayoutCompletedEvent") ?: return
        registry.emit(
            processId = process.id,
            phase = RunPhase.LAYOUT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Applied automatic diagram layout.",
        )
    }

    @EventListener
    fun onAlignmentChecked(event: BpmnAlignmentCheckedEvent) {
        val process = currentProcessOrWarn("BpmnAlignmentCheckedEvent") ?: return
        registry.emit(
            processId = process.id,
            phase = RunPhase.ALIGNMENT,
            artifactState = ArtifactState.XML_DRAFT,
            summary = "Checked semantic alignment (${event.report.verdict.name.lowercase()}).",
        )
    }

    // Milestone publication depends on AgentProcess.get(), a ThreadLocal bound by the agent
    // runtime to the thread executing the action. Plain @EventListener is synchronous, so the
    // listener fires on that same thread and the lookup resolves (mirrors BpmnPipelineObserver).
    private fun currentProcessOrWarn(source: String): AgentProcess? = resolveProcessOrWarn(logger, source)

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

// Milestone publication depends on AgentProcess.get(), a ThreadLocal bound by the agent runtime
// to the thread executing the action. Plain @EventListener is synchronous, so the listener
// fires on that same thread and the lookup resolves (mirrors the deleted BpmnPipelineObserver).
// Kept as a top-level function (not a class member) purely to stay under detekt's
// TooManyFunctions threshold for BpmnRunUpdateChannel.
private fun resolveProcessOrWarn(logger: Logger, source: String): AgentProcess? {
    val process = AgentProcess.get()
    if (process == null) {
        logger.warn("No AgentProcess bound to current thread while handling {}; RunUpdate dropped.", source)
    }
    return process
}
