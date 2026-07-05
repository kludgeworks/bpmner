/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.layout.BpmnLayoutCompletedEvent
import dev.groknull.bpmner.telemetry.BpmnSnapshotEvent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher

/**
 * Unit test for the LAYOUT_COMPLETE projection in [BpmnPipelineObserver].
 *
 * [BpmnPipelineObserver.onLayoutCompleted] resolves the active run via `AgentProcess.get()`,
 * a ThreadLocal bound by the agent runtime. The Companion's `set()` / `remove()` methods are
 * declared `internal` in Kotlin (scoped to the embabel module) but are `public final` in JVM
 * bytecode, so we bind/unbind via reflection — the bytecode contract is stable even if the
 * source-level modifier changes.
 */
class BpmnPipelineObserverTest {
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published.add(it) }
    private val observer = BpmnPipelineObserver(publisher)

    // AgentProcess.Companion.set/remove are public in JVM bytecode; access them via reflection.
    private val companionClass = AgentProcess.Companion.javaClass
    private val setMethod = companionClass.getMethod("set", AgentProcess::class.java)
    private val removeMethod = companionClass.getMethod("remove")

    private fun bindProcess(process: AgentProcess) = setMethod.invoke(AgentProcess.Companion, process)

    @AfterEach
    fun clearThreadLocal() {
        removeMethod.invoke(AgentProcess.Companion)
    }

    // -------------------------------------------------------------------------
    // LAYOUT_COMPLETE snapshot published when process is bound
    // -------------------------------------------------------------------------

    @Test
    fun `onLayoutCompleted publishes LAYOUT_COMPLETE BpmnSnapshotEvent with the laid-out xml`() {
        val process = mock(AgentProcess::class.java)
        val diXml = """<?xml version="1.0"?><bpmndi:BPMNDiagram />"""
        bindProcess(process)

        observer.onLayoutCompleted(BpmnLayoutCompletedEvent(xml = diXml))

        val snapshots = published.filterIsInstance<BpmnSnapshotEvent>()
        assertEquals(1, snapshots.size, "Expected exactly one BpmnSnapshotEvent")
        assertEquals("LAYOUT_COMPLETE", snapshots[0].stage)
        assertEquals(diXml, snapshots[0].xml)
        assertEquals(null, snapshots[0].attemptNumber)
        assertTrue(snapshots[0].diagnostics.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Drops the snapshot and logs warn when no process is bound
    // -------------------------------------------------------------------------

    @Test
    fun `onLayoutCompleted drops snapshot when no AgentProcess is bound`() {
        // No bindProcess() call — threadlocal is absent
        observer.onLayoutCompleted(BpmnLayoutCompletedEvent(xml = "<definitions/>"))

        assertTrue(
            published.isEmpty(),
            "Expected no events published when AgentProcess threadlocal is not bound",
        )
    }
}
