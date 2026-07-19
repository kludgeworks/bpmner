/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.agent.web.sse.SSEController
import com.embabel.agent.web.sse.SseProperties
import dev.groknull.bpmner.telemetry.BpmnClarificationRequestEvent
import dev.groknull.bpmner.telemetry.BpmnResultEvent
import dev.groknull.bpmner.telemetry.BpmnRunCostEvent
import dev.groknull.bpmner.telemetry.BpmnSnapshotEvent
import dev.groknull.bpmner.telemetry.BpmnStageEvent
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.ObjectProvider

/**
 * Drives the real production chain — a `Bpmn*Event` through [BpmnerEventToAgenticBridge] into a
 * directly-constructed [SSEController] (Embabel's own tested, Spring-context-free construction
 * path; no bpmner Spring context, no MockMvc/HTTP) — and asserts exactly-once, ordered delivery
 * with wire-contract fields intact (docs/architecture.md §wire-contract). This is the delivery-seam
 * contract test #524 item 1 calls for; the "exactly once" assertion is a regression guard against
 * #524's originally-shipped fan-out bug, where a since-removed `List<AgenticEventListener>` fan-out
 * also reached Embabel's aggregate listener bean and delivered every event twice.
 *
 * [SSEController.onProcessEvent] is spied on rather than observed via [SSEController]'s emitter/
 * buffer internals: the sink's own delivery mechanics (`SseEmitter`/`ResponseBodyEmitter`) require
 * the servlet async-dispatch machinery Embabel's own `SseControllerTest` also stops short of driving
 * in-process. Spying the real, directly-constructed controller reaches the same real sink method the
 * bridge calls and lets the argument captor read each delivered event's fields straight off the real
 * object — no serialization, no HTTP layer.
 */
class BpmnerEventToAgenticBridgeContractTest {

    private lateinit var sseController: SSEController
    private lateinit var bridge: BpmnerEventToAgenticBridge
    private val processId = "contract-test-proc"
    private val process = processWithId(processId)

    @BeforeEach
    fun setUp() {
        val realController = SSEController(SseProperties(AgentPlatformProperties()))
        sseController = spy(realController)
        sseController.streamEventsForId(processId)
        bridge = BpmnerEventToAgenticBridge(objectProviderOf(sseController))
    }

    @Test
    fun `delivers all four wire-contract event types exactly once, in order, with fields intact`() {
        val stageEvent = BpmnStageEvent(process, "readiness", "active", "Checking readiness")
        val snapshotEvent = BpmnSnapshotEvent(process, "generate", xml = "<bpmn/>", diagnostics = emptyList())
        val costEvent = BpmnRunCostEvent(process, "$0.02 / 1,204 tokens")
        val resultEvent = BpmnResultEvent(process, "GENERATED", null, null)

        bridge.onSpringEvent(stageEvent)
        bridge.onSpringEvent(snapshotEvent)
        bridge.onSpringEvent(costEvent)
        bridge.onSpringEvent(resultEvent)

        val captor = ArgumentCaptor.forClass(AgentProcessEvent::class.java)
        verify(sseController, times(4)).onProcessEvent(captor.capture() ?: stageEvent)
        val delivered = captor.allValues

        assertEquals(4, delivered.size)
        assertSame(stageEvent, delivered[0])
        assertSame(snapshotEvent, delivered[1])
        assertSame(costEvent, delivered[2])
        assertSame(resultEvent, delivered[3])

        val deliveredStage = delivered[0] as BpmnStageEvent
        assertEquals("readiness", deliveredStage.stage)
        assertEquals("active", deliveredStage.stageStatus)
        assertEquals("Checking readiness", deliveredStage.label)

        val deliveredSnapshot = delivered[1] as BpmnSnapshotEvent
        assertEquals("generate", deliveredSnapshot.stage)
        assertEquals(0, deliveredSnapshot.graphIssues)
        assertEquals(0, deliveredSnapshot.xsdIssues)
        assertEquals(0, deliveredSnapshot.lintIssues)

        val deliveredCost = delivered[2] as BpmnRunCostEvent
        assertEquals("$0.02 / 1,204 tokens", deliveredCost.costSummary)

        val deliveredResult = delivered[3] as BpmnResultEvent
        assertEquals("GENERATED", deliveredResult.resultStatus)
        assertNull(deliveredResult.alignmentVerdict)
        assertNull(deliveredResult.alignmentReport)
    }

    @Test
    fun `clarification-resume semantics — two rounds arrive in order with distinct round numbers`() {
        val round1 = BpmnClarificationRequestEvent(process, round = 1, maxRounds = 3, prompt = "What starts the process?")
        val round2 = BpmnClarificationRequestEvent(process, round = 2, maxRounds = 3, prompt = "What starts the process?")

        bridge.onSpringEvent(round1)
        bridge.onSpringEvent(round2)

        val captor = ArgumentCaptor.forClass(AgentProcessEvent::class.java)
        verify(sseController, times(2)).onProcessEvent(captor.capture() ?: round1)
        val delivered = captor.allValues.map { it as BpmnClarificationRequestEvent }

        assertEquals(listOf(1, 2), delivered.map { it.round })
        delivered.forEach { assertEquals(3, it.maxRounds) }
        assertEquals("What starts the process?", delivered[0].prompt)
        assertEquals("What starts the process?", delivered[1].prompt)
    }

    @Test
    fun `no throw when no emitter is registered for the process id`() {
        val noSubscriberController = SSEController(SseProperties(AgentPlatformProperties()))
        val noSubscriberBridge = BpmnerEventToAgenticBridge(objectProviderOf(noSubscriberController))
        val event = BpmnStageEvent(processWithId("no-subscriber-proc"), "readiness", "active", "Checking readiness")

        assertDoesNotThrow { noSubscriberBridge.onSpringEvent(event) }
    }

    private fun processWithId(id: String): AgentProcess {
        val process = mock(AgentProcess::class.java)
        `when`(process.id).thenReturn(id)
        return process
    }

    private fun objectProviderOf(controller: SSEController): ObjectProvider<SSEController> =
        object : ObjectProvider<SSEController> {
            override fun getIfAvailable(): SSEController = controller
        }
}
