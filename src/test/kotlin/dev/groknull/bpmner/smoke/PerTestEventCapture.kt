/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmInvocationEvent
import com.embabel.agent.api.event.LlmResponseEvent
import com.embabel.agent.api.event.ToolCallResponseEvent
import com.embabel.agent.core.LlmInvocation
import com.embabel.common.ai.model.PricingModel

/**
 * Accumulates every LLM call's cost/usage for the *currently running* smoke test, across **both** the
 * readiness and extraction stages.
 *
 * Wiring (see [ContractVocabularySmokeTest]): registered as a bean via `@Import`, so the readiness
 * invoker (`AgentPlatformBpmnReadinessInvoker`, which autowires `List<AgenticEventListener>`)
 * auto-collects it; the extraction call adds it explicitly to its `ProcessOptions.listeners` (that call
 * passes an explicit list, bypassing auto-collection). [SmokeResultRecorder] calls [reset] before each
 * test and [snapshot] after — safe because smoke tests run sequentially in a single JVM.
 *
 * `SuiteCostCapturer` is left untouched for the aggregate console total; this per-test capture is additive.
 */
class PerTestEventCapture : AgenticEventListener {
    private val lock = Any()
    private val invocations = mutableListOf<LlmInvocation>()
    private var llmResponseTimeMs = 0L
    private var finishedProcessToolCalls = 0
    private var toolResponseCalls = 0

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is LlmInvocationEvent -> synchronized(lock) { invocations.add(event.invocation) }
            is LlmResponseEvent<*> ->
                synchronized(lock) {
                    llmResponseTimeMs += event.runningTime.toMillis().coerceAtLeast(0)
                }
            is ToolCallResponseEvent -> synchronized(lock) { toolResponseCalls++ }
            is AgentProcessFinishedEvent ->
                synchronized(lock) {
                    finishedProcessToolCalls += event.agentProcess.toolsStats.toolsStats.values.sumOf { it.calls }
                }
            else -> Unit
        }
    }

    fun reset() {
        synchronized(lock) {
            invocations.clear()
            llmResponseTimeMs = 0L
            finishedProcessToolCalls = 0
            toolResponseCalls = 0
        }
    }

    fun snapshot(): Capture = synchronized(lock) {
        val invs = invocations.toList()
        Capture(
            costUsd = invs.sumOf { it.cost() },
            costKnown = costKnownOf(invs),
            promptTokens = invs.sumOf { it.usage.promptTokens ?: 0 },
            completionTokens = invs.sumOf { it.usage.completionTokens ?: 0 },
            llmCallCount = invs.size,
            llmTimeMs = llmTimeMsOf(invs),
            toolCallCount = if (finishedProcessToolCalls > 0) finishedProcessToolCalls else toolResponseCalls,
            servedModel = invs.map { it.llmMetadata.name }.distinct().joinToString(",").ifEmpty { null },
            stageBreakdown =
            invs.groupBy { it.agentName ?: UNKNOWN }
                .mapValues { (_, group) ->
                    StageStats(
                        model = group.map { it.llmMetadata.name }.distinct().joinToString(","),
                        promptTokens = group.sumOf { it.usage.promptTokens ?: 0 },
                        completionTokens = group.sumOf { it.usage.completionTokens ?: 0 },
                        llmCalls = group.size,
                    )
                },
        )
    }

    /**
     * Embabel 0.4.0 can emit zero-valued [LlmInvocation.runningTime] for provider-backed calls while
     * [LlmResponseEvent.runningTime] is populated. Prefer response timing and keep invocation timing as
     * a compatibility fallback.
     */
    private fun llmTimeMsOf(invs: List<LlmInvocation>): Long {
        if (llmResponseTimeMs > 0) return llmResponseTimeMs
        return invs.sumOf { it.runningTime.toMillis().coerceAtLeast(0) }
    }

    /**
     * Tri-state so a `$0` row is unambiguous: `unknown` = pricing not configured (e.g. a custom Groq
     * model without an explicit `PricingModel`); `free` = a genuinely-free local model
     * (`ALL_YOU_CAN_EAT`); `priced` = real per-token pricing.
     */
    private fun costKnownOf(invs: List<LlmInvocation>): String = when {
        invs.isEmpty() -> UNKNOWN
        invs.any { it.llmMetadata.pricingModel == null } -> UNKNOWN
        invs.all { it.llmMetadata.pricingModel == PricingModel.ALL_YOU_CAN_EAT } -> "free"
        else -> "priced"
    }

    data class Capture(
        val costUsd: Double,
        val costKnown: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val llmCallCount: Int,
        val llmTimeMs: Long,
        val toolCallCount: Int,
        val servedModel: String?,
        val stageBreakdown: Map<String, StageStats>,
    )

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
