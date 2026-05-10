package dev.groknull.bpmner.repair

import dev.groknull.bpmner.generation.BpmnGeneratedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BpmnRepairEventListener {
    private val logger = LoggerFactory.getLogger(BpmnRepairEventListener::class.java)

    @EventListener
    fun onBpmnGenerated(event: BpmnGeneratedEvent) {
        logger.debug(
            "BPMN generated, repair phase starting: request={}, definitionNodes={}",
            event.request.outputFile,
            event.rendered.definition.nodes.size,
        )
    }
}
