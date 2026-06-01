/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.common.ToolStats
import com.embabel.agent.api.common.ToolsStats
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.LlmInvocationHistory

/**
 * Accumulates the `LlmInvocation`s and tool stats of every finished run across a smoke suite, so the
 * suite total can be rendered with the framework's own [LlmInvocationHistory.costInfoString] — no
 * Micrometer, no hand-rolled cost formatting. Passed to each run via
 * `ProcessOptions(listeners = listOf(this))` so capture is deterministic (independent of any
 * globally-registered listener).
 */
object SuiteCostCapturer : AgenticEventListener {
    private val lock = Any()
    private val invocations = mutableListOf<LlmInvocation>()
    private val toolStatsByRun = mutableListOf<Map<String, ToolStats>>()
    private var runs = 0

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        synchronized(lock) {
            invocations.addAll(event.agentProcess.llmInvocations)
            toolStatsByRun.add(event.agentProcess.toolsStats.toolsStats.toMap())
            runs++
        }
    }

    fun reset() {
        synchronized(lock) {
            invocations.clear()
            toolStatsByRun.clear()
            runs = 0
        }
    }

    fun runCount(): Int = synchronized(lock) { runs }

    /** Framework-rendered cost/usage summary aggregated across every captured run. */
    fun aggregateCostSummary(): String {
        val history = synchronized(lock) {
            AggregateLlmInvocationHistory(invocations.toList(), MergedToolsStats(mergeToolStats(toolStatsByRun)))
        }
        return history.costInfoString(verbose = true)
    }
}

/**
 * A complete [LlmInvocationHistory] over the suite's flattened invocations and merged tool stats. Its
 * inherited `cost()`/`usage()`/`modelsUsed()`/`costInfoString()` aggregate the `llmInvocations`, and
 * `toolsStats` carries the per-tool totals merged across runs.
 */
private data class AggregateLlmInvocationHistory(
    override val llmInvocations: List<LlmInvocation>,
    override val toolsStats: ToolsStats,
) : LlmInvocationHistory

private class MergedToolsStats(
    override val toolsStats: Map<String, ToolStats>,
) : ToolsStats

/** Merges per-run tool-stats maps by tool name: summed calls/failures, call-weighted average response time. */
private fun mergeToolStats(perRun: List<Map<String, ToolStats>>): Map<String, ToolStats> {
    val merged = mutableMapOf<String, ToolStats>()
    for (run in perRun) {
        for ((name, stats) in run) {
            val existing = merged[name]
            merged[name] = if (existing == null) stats else combine(existing, stats)
        }
    }
    return merged
}

private fun combine(a: ToolStats, b: ToolStats): ToolStats {
    val calls = a.calls + b.calls
    val averageResponseTime =
        if (calls > 0) (a.averageResponseTime * a.calls + b.averageResponseTime * b.calls) / calls else 0L
    return ToolStats(
        name = a.name,
        calls = calls,
        averageResponseTime = averageResponseTime,
        failures = a.failures + b.failures,
    )
}
