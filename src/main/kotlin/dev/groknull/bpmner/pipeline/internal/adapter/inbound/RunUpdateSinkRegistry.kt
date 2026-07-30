/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.pipeline.RunUpdate
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * bpmner-owned, per-processId registry of [RunUpdate] sinks (plan D2 — supersedes ADR-ss-004:
 * a minimal bpmner-side registry is now warranted, fed by the [dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnRunUpdateChannel]
 * anti-corruption layer, not read from Embabel's `web.sse` buffering).
 *
 * Each sink is a **replay** buffer (D4): the async run returns 202 and the browser subscribes
 * over a separate GET afterwards, so a live-only sink would drop every update published before
 * the subscription lands. [REPLAY_LIMIT] and [MAX_PROCESS_BUFFERS] mirror the platform's own
 * `embabel.agent.platform.sse.max-buffer-size` (100) / `.max-process-buffers` (1000) defaults
 * (ADR-605-06) — bounded and evictable, not an unbounded registry.
 *
 * Sequence numbers are assigned here, by a single writer per process (D3): the run is
 * single-threaded per process today (`SimpleAgentProcess`), and even if `ConcurrentAgentProcess`
 * is enabled later, ordering stays authoritative at this one write point rather than at browser
 * arrival.
 */
@InfrastructureRing
@Component
internal class RunUpdateSinkRegistry {
    private class ProcessSink {
        val seq = AtomicLong(0)
        val sink: Sinks.Many<RunUpdate> = Sinks.many().replay().limit(REPLAY_LIMIT)
    }

    private val sinks = ConcurrentHashMap<String, ProcessSink>()

    /** Last-known phase/artifact per process, so a bare narration ([emitNarration]) can be placed in context. */
    private val lastKnown = ConcurrentHashMap<String, Pair<RunPhase, ArtifactState>>()

    /** Publishes an ordered, non-terminal [RunUpdate.Progress] for [processId]. */
    fun emit(
        processId: String,
        phase: RunPhase,
        artifactState: ArtifactState,
        summary: String,
        detail: Map<String, String> = emptyMap(),
    ) {
        lastKnown[processId] = phase to artifactState
        val processSink = sinkFor(processId)
        processSink.sink.tryEmitNext(
            RunUpdate.Progress(
                seq = processSink.seq.incrementAndGet(),
                phase = phase,
                artifactState = artifactState,
                summary = summary,
                detail = detail,
            ),
        )
    }

    /**
     * Publishes a transient narration string in the run's last-known phase/artifact context —
     * the extension point for optional LLM-authored narration (ADR-605-04's
     * `MessageOutputChannelEvent` / Embabel's [com.embabel.agent.api.channel.ProgressOutputChannelEvent]),
     * without any new port. Falls back to [RunPhase.READINESS] / [ArtifactState.NONE] if no
     * milestone has been recorded yet for this process.
     */
    fun emitNarration(processId: String, message: String) {
        val (phase, artifactState) = lastKnown[processId] ?: (RunPhase.READINESS to ArtifactState.NONE)
        emit(processId, phase, artifactState, message)
    }

    /**
     * Publishes the single terminal [RunUpdate.Terminal] for [processId] and completes its
     * sink — no further updates are possible for this process afterwards.
     */
    fun emitTerminal(
        processId: String,
        artifactState: ArtifactState,
        summary: String,
        outcome: RunOutcome,
        detail: Map<String, String> = emptyMap(),
    ) {
        lastKnown.remove(processId)
        val processSink = sinkFor(processId)
        processSink.sink.tryEmitNext(
            RunUpdate.Terminal(
                seq = processSink.seq.incrementAndGet(),
                artifactState = artifactState,
                summary = summary,
                outcome = outcome,
                detail = detail,
            ),
        )
        processSink.sink.tryEmitComplete()
    }

    /**
     * Subscribes to the ordered replay-then-live [RunUpdate] stream for [processId] — the sole
     * read side the SSE endpoint uses. Subscribing before any update has been emitted is safe:
     * a sink is created lazily and simply has nothing to replay yet.
     */
    fun subscribe(processId: String): Flux<RunUpdate> = sinkFor(processId).sink.asFlux()

    private fun sinkFor(processId: String): ProcessSink = sinks.computeIfAbsent(processId) {
        evictOneIfFull()
        ProcessSink()
    }

    // Bounded/evictable (D2/ADR-605-06): drop one sink with no active subscriber before growing
    // past the platform-mirrored cap, rather than retaining every process forever.
    private fun evictOneIfFull() {
        if (sinks.size < MAX_PROCESS_BUFFERS) return
        sinks.entries.firstOrNull { it.value.sink.currentSubscriberCount() == 0 }
            ?.let { sinks.remove(it.key) }
    }

    companion object {
        private const val REPLAY_LIMIT = 100
        private const val MAX_PROCESS_BUFFERS = 1000
    }
}
