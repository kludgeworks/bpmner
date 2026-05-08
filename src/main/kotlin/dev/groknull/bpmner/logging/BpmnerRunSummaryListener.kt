package dev.groknull.bpmner.logging

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class BpmnerRunSummaryListener : AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryListener::class.java)

    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event !is AgentProcessFinishedEvent) return
        val p = event.agentProcess
        val usage = p.usage()
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
