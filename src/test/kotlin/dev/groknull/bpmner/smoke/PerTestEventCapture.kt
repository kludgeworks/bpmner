/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmInvocationEvent
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
    private var toolCalls = 0

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is LlmInvocationEvent -> synchronized(lock) { invocations.add(event.invocation) }
            is AgentProcessFinishedEvent ->
                synchronized(lock) {
                    toolCalls += event.agentProcess.toolsStats.toolsStats.values.sumOf { it.calls }
                }
            else -> Unit
        }
    }

    fun reset() {
        synchronized(lock) {
            invocations.clear()
            toolCalls = 0
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
            llmTimeMs = invs.sumOf { it.runningTime.toMillis() },
            toolCallCount = toolCalls,
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
