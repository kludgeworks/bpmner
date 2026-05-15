package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgentProcessFinishedEvent
import com.embabel.agent.api.event.AgenticEventListener
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Path

@PrimaryAdapter
@Component
class BpmnerRunSummaryListener(
    private val config: BpmnConfig,
    private val validationEvents: BpmnerValidationEventCollector,
    private val jsonlAppender: BpmnerRunSummaryJsonlAppender,
) : AgenticEventListener {
    private val logger = LoggerFactory.getLogger(BpmnerRunSummaryListener::class.java)
    private val summaryFactory = BpmnerStructuredRunSummaryFactory()

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
        val request =
            p.blackboard.objects
                .filterIsInstance<BpmnRequest>()
                .lastOrNull()
        val collectedEvents = validationEvents.removeFor(request)
        jsonlAppender.append(
            logDir = Path.of(config.logging.dir),
            summary =
                summaryFactory.from(
                    process = p,
                    eventType = event::class.simpleName ?: "AgentProcessFinishedEvent",
                    validationEvents = collectedEvents,
                ),
        )
    }
}
