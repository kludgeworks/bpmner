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
import com.embabel.agent.api.event.AgentProcessStuckEvent
import com.embabel.agent.api.event.AgentProcessWaitingEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.hitl.FormBindingRequest
import com.embabel.ux.form.RadioGroup
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.conformance.format
import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-corruption layer, Embabel-facing half: the Embabel [OutputChannel] registered on every
 * run's `ProcessOptions`, plus the platform [AgenticEventListener] for process lifecycle
 * (waiting/failed/finished), translated into the ordered
 * [dev.groknull.bpmner.pipeline.RunUpdate] stream in [RunUpdateSinkRegistry]. The bpmner-facing
 * half — bpmner's own `@DomainEvent` milestones — is [BpmnMilestoneEventListener], a separate
 * `@Component` with no Embabel imports.
 *
 * Imports `com.embabel.agent.api.channel.*`/`api.event.*` only, never `web.sse.*`; emits only
 * [dev.groknull.bpmner.pipeline.RunUpdate] outward — no Embabel type, action name, prompt,
 * credential, or provider payload in `detail`.
 */
@InfrastructureRing
@Component
internal class BpmnRunUpdateChannel(
    private val registry: RunUpdateSinkRegistry,
) : OutputChannel,
    AgenticEventListener {
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
            // A stuck process has no plan to reach any goal and will emit nothing further.
            // It is not a "finished" event, so without this branch the run goes silent and a
            // subscriber waits forever.
            is AgentProcessStuckEvent -> onStuck(event.agentProcess)
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

    private fun onStuck(process: AgentProcess) {
        clarificationRounds.remove(process.id)
        registry.emitTerminal(
            processId = process.id,
            artifactState = ArtifactState.NONE,
            summary = "BPMN generation could not continue.",
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
                result.failureDetail?.takeIf { it.isNotBlank() }?.let { put("failureDetail", it) }
                result.alignmentReport?.verdict?.name?.let { put("alignmentVerdict", it) }
                result.alignmentReport?.rationale?.let { put("alignmentReport", it) }
                result.validationDiagnostics
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { diagnostics -> put("diagnostics", diagnostics.joinToString("\n") { it.format() }) }
            },
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
    // LAYOUT_FAILED still carries the laid-out XML, so the client has something to show.
    BpmnGenerationStatus.VALIDATION_FAILED, BpmnGenerationStatus.LAYOUT_FAILED -> ArtifactState.DIAGNOSTIC
    BpmnGenerationStatus.NEEDS_CLARIFICATION,
    BpmnGenerationStatus.READINESS_FAILED,
    BpmnGenerationStatus.CONTRACT_FAILED,
    BpmnGenerationStatus.OUTLINE_FAILED,
    -> ArtifactState.NONE
}

private fun summaryFor(status: BpmnGenerationStatus): String = when (status) {
    BpmnGenerationStatus.GENERATED -> "BPMN generation complete."
    BpmnGenerationStatus.NEEDS_CLARIFICATION -> "Needs clarification — generation stopped."
    BpmnGenerationStatus.ALIGNMENT_FAILED -> "Alignment failed — reviewing the generated BPMN."
    BpmnGenerationStatus.VALIDATION_FAILED -> "Validation failed — generation stopped."
    BpmnGenerationStatus.READINESS_FAILED -> "Readiness assessment failed — generation stopped."
    BpmnGenerationStatus.CONTRACT_FAILED -> "Could not extract a valid process contract — generation stopped."
    BpmnGenerationStatus.OUTLINE_FAILED -> "Could not draft the process — generation stopped."
    BpmnGenerationStatus.LAYOUT_FAILED -> "Diagram layout failed — generation stopped."
}
