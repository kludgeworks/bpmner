/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent
import com.embabel.agent.spi.logging.LoggingAgenticEventListener
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
class BpmnerLoggingAgenticEventListener : LoggingAgenticEventListener() {
    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is AgentProcessReadyToPlanEvent -> {
                logger.debug(getAgentProcessReadyToPlanEventMessage(event))
            }

            is AgentProcessPlanFormulatedEvent -> {
                logger.debug(getAgentProcessPlanFormulatedEventMessage(event))
            }

            else -> {
                super.onProcessEvent(event)
            }
        }
    }
}
