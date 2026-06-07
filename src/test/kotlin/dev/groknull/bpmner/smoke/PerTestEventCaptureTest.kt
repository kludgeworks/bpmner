/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.common.ToolStats
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.event.AgentProcessFailedEvent
import com.embabel.agent.api.event.LlmInvocationEvent
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.core.Action
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.common.ai.model.LlmMetadata
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

class PerTestEventCaptureTest {

    @Test
    fun `uses response event duration when invocation duration is zero`() {
        val capture = PerTestEventCapture()

        capture.onProcessEvent(invocationEvent(runningTime = Duration.ZERO))
        capture.onProcessEvent(llmResponseEvent(Duration.ofMillis(1_250)))

        val snap = capture.snapshot()

        assertEquals(1, snap.llmCallCount)
        assertEquals(1_250, snap.llmTimeMs)
    }

    @Test
    fun `falls back to invocation duration when no response duration was captured`() {
        val capture = PerTestEventCapture()

        capture.onProcessEvent(invocationEvent(runningTime = Duration.ofMillis(875)))

        assertEquals(875, capture.snapshot().llmTimeMs)
    }

    @Test
    fun `reset clears invocation response time and tool counters`() {
        val capture = PerTestEventCapture()

        capture.onProcessEvent(invocationEvent(runningTime = Duration.ofMillis(500)))
        capture.onProcessEvent(llmResponseEvent(Duration.ofMillis(750)))
        capture.onProcessEvent(toolCallResponseEvent())
        capture.reset()

        val snap = capture.snapshot()

        assertEquals(0, snap.llmCallCount)
        assertEquals(0, snap.llmTimeMs)
        assertEquals(0, snap.toolCallCount)
    }

    @Test
    fun `uses finished process tool stats when populated`() {
        val capture = PerTestEventCapture()
        val process = processWithTools(
            ToolStats(name = "lookup", calls = 2, averageResponseTime = 10, failures = 0),
            ToolStats(name = "write", calls = 1, averageResponseTime = 20, failures = 0),
        )

        capture.onProcessEvent(toolCallResponseEvent())
        capture.onProcessEvent(AgentProcessFailedEvent(process))

        assertEquals(3, capture.snapshot().toolCallCount)
    }

    @Test
    fun `falls back to tool response events when finished process stats are empty`() {
        val capture = PerTestEventCapture()

        capture.onProcessEvent(toolCallResponseEvent())
        capture.onProcessEvent(toolCallResponseEvent())
        capture.onProcessEvent(AgentProcessFailedEvent(processWithTools()))

        assertEquals(2, capture.snapshot().toolCallCount)
    }

    private fun invocationEvent(runningTime: Duration): LlmInvocationEvent = LlmInvocationEvent(
        mock(AgentProcess::class.java),
        LlmInvocation(
            LlmMetadata.create("test-provider", "test-model", null, PricingModel.ALL_YOU_CAN_EAT),
            Usage(promptTokens = 100, completionTokens = 25, nativeUsage = null),
            "test-agent",
            Instant.parse("2026-06-07T00:00:00Z"),
            runningTime,
        ),
        "interaction-1",
    )

    private fun llmResponseEvent(runningTime: Duration) = LlmRequestEvent(
        mock(AgentProcess::class.java),
        mock(Action::class.java),
        String::class.java,
        LlmInteraction.using(LlmOptions.withModel("test-model")),
        LlmMetadata.create("test-provider", "test-model", null, PricingModel.ALL_YOU_CAN_EAT),
        emptyList(),
    ).responseEvent("ok", runningTime)

    private fun toolCallResponseEvent() = ToolCallRequestEvent(
        mock(AgentProcess::class.java),
        mock(Action::class.java),
        "lookup",
        mock(ToolGroupMetadata::class.java),
        "{}",
        LlmOptions.withModel("test-model"),
        "correlation-1",
    ).responseEvent(runCatching { "ok" }, Duration.ofMillis(10))

    private fun processWithTools(vararg stats: ToolStats): AgentProcess {
        val process = mock(AgentProcess::class.java)
        val toolsStats = object : ToolsStats {
            override val toolsStats: Map<String, ToolStats> = stats.associateBy { it.name }
        }
        `when`(process.toolsStats).thenReturn(toolsStats)
        return process
    }
}
