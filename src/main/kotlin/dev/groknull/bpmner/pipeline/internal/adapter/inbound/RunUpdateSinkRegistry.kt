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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * bpmner-owned, per-processId registry of [RunUpdate] sinks, fed by [BpmnRunUpdateChannel] and
 * [BpmnMilestoneEventListener] — the ACL's two halves.
 *
 * Each sink is a bounded **replay** buffer (a late subscriber must not miss updates already
 * published), mirroring the platform's own `max-buffer-size` (100) / `max-process-buffers`
 * (1000) defaults: [sinkFor] always evicts (oldest-unsubscribed, else oldest) before admitting a
 * sink past [MAX_PROCESS_BUFFERS], so the registry can never grow unbounded.
 *
 * Sequence numbers are assigned here by a single writer per process — authoritative regardless
 * of dispatch mode, rather than at browser arrival.
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

    /**
     * Processes that have already emitted their terminal. This is bounded independently of
     * [sinks]: a late duplicate after the process sink was evicted must still be dropped.
     */
    private val terminated = ConcurrentHashMap.newKeySet<String>()
    private val terminatedOrder = ConcurrentLinkedQueue<String>()

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
        val update = RunUpdate.Progress(
            seq = processSink.seq.incrementAndGet(),
            phase = phase,
            artifactState = artifactState,
            summary = summary,
            detail = detail,
        )
        logUpdate(processId, update)
        processSink.sink.tryEmitNext(update)
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
     *
     * A run can reach a terminal by more than one route (a typed result, a platform failure
     * event, or the abort backstop), and more than one may fire for the same run. The first
     * wins and the rest are dropped, so a consumer always sees exactly one terminal.
     */
    fun emitTerminal(
        processId: String,
        artifactState: ArtifactState,
        summary: String,
        outcome: RunOutcome,
        detail: Map<String, String> = emptyMap(),
    ) {
        if (!terminated.add(processId)) {
            logger.debug("Terminal already emitted for {}; dropping duplicate ({})", processId, summary)
            return
        }
        terminatedOrder.add(processId)
        evictOldestTerminatedIfFull()
        lastKnown.remove(processId)
        val processSink = sinkFor(processId)
        val update = RunUpdate.Terminal(
            seq = processSink.seq.incrementAndGet(),
            artifactState = artifactState,
            summary = summary,
            outcome = outcome,
            detail = detail,
        )
        logUpdate(processId, update)
        processSink.sink.tryEmitNext(update)
        processSink.sink.tryEmitComplete()
    }

    /**
     * Subscribes to the ordered replay-then-live [RunUpdate] stream for [processId] — the sole
     * read side the SSE endpoint uses. Subscribing before any update has been emitted is safe:
     * a sink is created lazily and simply has nothing to replay yet.
     */
    fun subscribe(processId: String): Flux<RunUpdate> = sinkFor(processId).sink.asFlux()

    // Mirrors the flat wire shape the SSE endpoint serializes (seq/phase/artifactState/outcome?/
    // summary/detail), so the log is a faithful, greppable stand-in for the bytes the browser
    // receives — useful for tracing a run's ordered stream without attaching to the SSE endpoint.
    // DEBUG so it is off in normal INFO operation; enable by keeping this class at DEBUG.
    private fun logUpdate(processId: String, update: RunUpdate) {
        if (!logger.isDebugEnabled) return
        val outcome = (update as? RunUpdate.Terminal)?.outcome?.let { " outcome=$it" } ?: ""
        logger.debug(
            "RunUpdate[{}] seq={} phase={} artifactState={}{} summary=\"{}\" detail={}",
            processId,
            update.seq,
            update.phase,
            update.artifactState,
            outcome,
            update.summary,
            update.detail,
        )
    }

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

    private fun evictOldestTerminatedIfFull() {
        while (terminated.size > MAX_TERMINATED) {
            val oldest = terminatedOrder.poll() ?: break
            terminated.remove(oldest)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RunUpdateSinkRegistry::class.java)
        private const val REPLAY_LIMIT = 100
        private const val MAX_PROCESS_BUFFERS = 1000
        private const val MAX_TERMINATED = MAX_PROCESS_BUFFERS * 5
    }
}
