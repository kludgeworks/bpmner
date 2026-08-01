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
 * bpmner-owned, per-processId registry of [RunUpdate] sinks, fed by [BpmnRunUpdateChannel] and
 * [BpmnMilestoneEventListener] — the two halves of the anti-corruption layer that share this
 * registry.
 *
 * Each sink is a **replay** buffer: a subscriber may connect after updates have already been
 * published for a process, so a live-only sink would drop everything published before the
 * subscription lands. [REPLAY_LIMIT] and [MAX_PROCESS_BUFFERS] mirror the platform's own
 * `embabel.agent.platform.sse.max-buffer-size` (100) / `.max-process-buffers` (1000) defaults —
 * bounded and evictable, never an unbounded registry: [sinkFor] always evicts a sink before
 * admitting one past the cap, picking the oldest unsubscribed sink if one exists and otherwise
 * the oldest sink outright, so the registry can never grow past [MAX_PROCESS_BUFFERS].
 *
 * Sequence numbers are assigned here, by a single writer per process: the run is single-threaded
 * per process today (`SimpleAgentProcess`), and even if `ConcurrentAgentProcess` is enabled
 * later, ordering stays authoritative at this one write point rather than at browser arrival.
 */
@InfrastructureRing
@Component
internal class RunUpdateSinkRegistry {
    private class ProcessSink(val createdAt: Long) {
        val seq = AtomicLong(0)
        val sink: Sinks.Many<RunUpdate> = Sinks.many().replay().limit(REPLAY_LIMIT)
    }

    private val creationCounter = AtomicLong(0)
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
     * the extension point for optional LLM-authored narration, without any new port. Falls back
     * to [RunPhase.READINESS] / [ArtifactState.NONE] if no milestone has been recorded yet for
     * this process.
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
        ProcessSink(creationCounter.incrementAndGet())
    }

    // Evicts before growing past the cap: prefers the oldest unsubscribed sink, else the oldest.
    private fun evictOneIfFull() {
        if (sinks.size < MAX_PROCESS_BUFFERS) return
        val victim = sinks.entries.filter { it.value.sink.currentSubscriberCount() == 0 }
            .minByOrNull { it.value.createdAt }
            ?: sinks.entries.minByOrNull { it.value.createdAt }
        victim?.let { sinks.remove(it.key) }
    }

    companion object {
        private const val REPLAY_LIMIT = 100
        private const val MAX_PROCESS_BUFFERS = 1000
    }
}
