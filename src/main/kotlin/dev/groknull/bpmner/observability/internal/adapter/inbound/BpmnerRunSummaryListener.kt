/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import jakarta.annotation.PreDestroy
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
class BpmnerRunSummaryListener : AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryListener::class.java)

    private var totalCost = 0.0
    private var totalPromptTokens = 0L
    private var totalCompletionTokens = 0L
    private var totalActions = 0L
    private var totalRuns = 0L

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val p = event.agentProcess
        val usage = p.usage()

        synchronized(this) {
            totalCost += p.cost()
            totalPromptTokens += usage.promptTokens ?: 0
            totalCompletionTokens += usage.completionTokens ?: 0
            totalActions += p.history.size
            totalRuns++
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

    @PreDestroy
    fun printSummary() {
        synchronized(this) {
            if (totalRuns == 0L) return
            val message = """

                ======================================================================
                TOTAL SUITE SUMMARY ($totalRuns run(s) complete)
                ======================================================================
                Total LLM Cost:   $${"%.4f".format(totalCost)}
                Total Tokens:     $totalPromptTokens prompt / $totalCompletionTokens completion
                Total Actions:    $totalActions
                ======================================================================

            """.trimIndent()
            logger.info(message)
            println(message)
        }
    }
}
