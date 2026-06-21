/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Logs a per-run summary when an agent process finishes, using the framework's own renderers:
 * [com.embabel.agent.core.LlmInvocationHistory.costInfoString] for LLMs/tokens/cost and
 * [com.embabel.agent.core.ActionInvocation.infoString] for per-action timings. This is the only
 * per-run cost summary surfaced in shell and server logs (bpmner's shell/web bypass embabel's
 * `formatProcessOutput`).
 */
@PrimaryAdapter
@Component
class BpmnerRunSummaryListener : AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryListener::class.java)

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val p = event.agentProcess

        logger.info("Run complete in {}s\n{}", p.runningTime.seconds, p.costInfoString(verbose = true))
        // Per-action timings are diagnostic noise for the user — keep them in the log file (DEBUG) but
        // off the console (whose threshold is INFO).
        if (p.history.isNotEmpty()) {
            logger.debug("Actions: {}", p.history.joinToString(", ") { it.infoString(verbose = true, indent = 0) })
        }
    }
}
