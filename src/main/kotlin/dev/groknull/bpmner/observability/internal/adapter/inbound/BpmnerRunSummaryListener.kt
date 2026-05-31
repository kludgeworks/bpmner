/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import io.micrometer.core.instrument.MeterRegistry
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnerRunSummaryListener(
    private val meterRegistryProvider: ObjectProvider<MeterRegistry>,
) : AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryListener::class.java)

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val p = event.agentProcess
        val usage = p.usage()

        // Record standard Micrometer metrics if available
        val meterRegistry = meterRegistryProvider.getIfAvailable()
        if (meterRegistry != null) {
            meterRegistry.counter("bpmner.llm.cost").increment(p.cost())
            meterRegistry.counter("bpmner.llm.tokens", "type", "prompt").increment((usage.promptTokens ?: 0).toDouble())
            meterRegistry.counter("bpmner.llm.tokens", "type", "completion").increment((usage.completionTokens ?: 0).toDouble())
            meterRegistry.counter("bpmner.llm.actions").increment(p.history.size.toDouble())
            meterRegistry.counter("bpmner.llm.runs").increment(1.0)
        }

        logger.info(
            "Run complete in {}s | actions={} | models={} | cost=\${} | tokens={}prompt/{}completion",
            p.runningTime.seconds,
            p.history.size,
            p.modelsUsed().joinToString { it.name },
            "%.4f".format(p.cost()),
            usage.promptTokens ?: 0,
            usage.completionTokens ?: 0,
        )
        p.history.forEach { action ->
            logger.info("  {} {}ms", action.actionName.substringAfterLast("."), action.runningTime.toMillis())
        }
    }
}
