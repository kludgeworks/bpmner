/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import dev.groknull.bpmner.pipeline.ArtifactState
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunPhase
import dev.groknull.bpmner.pipeline.RunUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class RunUpdateSinkRegistryTest {
    private val registry = RunUpdateSinkRegistry()

    @Test
    fun `emit assigns a strictly increasing seq per process`() {
        registry.emit("p1", RunPhase.READINESS, ArtifactState.NONE, "one")
        registry.emit("p1", RunPhase.CONTRACT, ArtifactState.NONE, "two")
        registry.emit("p1", RunPhase.DRAFT, ArtifactState.XML_DRAFT, "three")

        val updates = registry.subscribe("p1").take(3).collectList().block(TIMEOUT)!!

        assertEquals(listOf(1L, 2L, 3L), updates.map { it.seq })
        assertEquals(listOf("one", "two", "three"), updates.map { it.summary })
    }

    @Test
    fun `different processIds have independent sequence counters`() {
        registry.emit("a", RunPhase.READINESS, ArtifactState.NONE, "a1")
        registry.emit("b", RunPhase.READINESS, ArtifactState.NONE, "b1")
        registry.emit("a", RunPhase.CONTRACT, ArtifactState.NONE, "a2")

        val aUpdates = registry.subscribe("a").take(2).collectList().block(TIMEOUT)!!
        val bUpdates = registry.subscribe("b").take(1).collectList().block(TIMEOUT)!!

        assertEquals(listOf(1L, 2L), aUpdates.map { it.seq })
        assertEquals(listOf(1L), bUpdates.map { it.seq })
    }

    @Test
    fun `a late subscriber replays every prior update before any live one, in order`() {
        // Emitted with no subscriber attached yet — the exact D4 scenario: the async run
        // returns 202 and starts emitting before the browser has connected.
        registry.emit("late", RunPhase.READINESS, ArtifactState.NONE, "first")
        registry.emit("late", RunPhase.CONTRACT, ArtifactState.NONE, "second")

        val replayed = registry.subscribe("late").take(2).collectList().block(TIMEOUT)!!

        assertEquals(listOf("first", "second"), replayed.map { it.summary })
        assertEquals(listOf(1L, 2L), replayed.map { it.seq })
    }

    @Test
    fun `emitTerminal publishes the terminal update and completes the sink`() {
        registry.emit("term", RunPhase.READINESS, ArtifactState.NONE, "progressing")
        registry.emitTerminal("term", ArtifactState.FINAL, "done", RunOutcome.COMPLETED)

        // collectList() only resolves once the Flux completes — proving the sink was closed,
        // not merely that two items arrived.
        val updates = registry.subscribe("term").collectList().block(TIMEOUT)!!

        assertEquals(2, updates.size)
        val terminal = updates[1] as RunUpdate.Terminal
        assertEquals(RunOutcome.COMPLETED, terminal.outcome)
        assertEquals(ArtifactState.FINAL, terminal.artifactState)
        assertEquals(2L, terminal.seq)
    }

    @Test
    fun `emitNarration places a bare message in the run's last-known phase and artifact state`() {
        registry.emit("narrate", RunPhase.LAYOUT, ArtifactState.XML_DRAFT, "layout done")
        registry.emitNarration("narrate", "still laying things out")

        val updates = registry.subscribe("narrate").take(2).collectList().block(TIMEOUT)!!

        val narration = updates[1]
        assertEquals(RunPhase.LAYOUT, narration.phase)
        assertEquals(ArtifactState.XML_DRAFT, narration.artifactState)
        assertEquals("still laying things out", narration.summary)
    }

    @Test
    fun `emitNarration before any milestone falls back to READINESS NONE`() {
        registry.emitNarration("fresh", "hello")

        val update = registry.subscribe("fresh").take(1).collectList().block(TIMEOUT)!!.single()

        assertEquals(RunPhase.READINESS, update.phase)
        assertEquals(ArtifactState.NONE, update.artifactState)
    }

    @Test
    fun `subscribing to an unknown process id does not throw and yields a fresh empty sink`() {
        val flux = registry.subscribe("never-emitted-to")
        registry.emit("never-emitted-to", RunPhase.READINESS, ArtifactState.NONE, "hi")

        val updates = flux.take(1).collectList().block(TIMEOUT)!!
        assertTrue(updates.single().summary == "hi")
    }

    @Test
    fun `never grows past MAX_PROCESS_BUFFERS even when every existing sink is subscribed`() {
        // Every sink below is kept subscribed for its whole lifetime, so an eviction candidate
        // with zero subscribers never exists — the exact scenario that let the registry grow
        // unbounded before the oldest-sink fallback was added.
        val subscriptions = (0 until MAX_PROCESS_BUFFERS).map { i ->
            registry.subscribe("full-$i").subscribe()
        }

        // One more distinct id past the cap must still evict something rather than add a 1001st.
        registry.subscribe("one-too-many").subscribe()

        assertEquals(MAX_PROCESS_BUFFERS, registrySize())

        subscriptions.forEach { it.dispose() }
    }

    private fun registrySize(): Int {
        val sinksField = RunUpdateSinkRegistry::class.java.getDeclaredField("sinks")
        sinksField.isAccessible = true
        return (sinksField.get(registry) as Map<*, *>).size
    }

    private companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(5)
        private const val MAX_PROCESS_BUFFERS = 1000
    }
}
