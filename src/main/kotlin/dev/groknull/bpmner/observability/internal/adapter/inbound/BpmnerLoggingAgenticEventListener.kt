package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent
import com.embabel.agent.api.event.AgentProcessReadyToPlanEvent
import com.embabel.agent.spi.logging.LoggingAgenticEventListener
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.springframework.stereotype.Component

@PrimaryAdapter
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
