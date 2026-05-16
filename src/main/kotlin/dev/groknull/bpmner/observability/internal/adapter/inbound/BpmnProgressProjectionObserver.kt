package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.ProgressUpdateEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BpmnProgressProjectionObserver(
    private val eventPublisher: ApplicationEventPublisher,
) {
    @EventListener
    fun onActionStart(event: ActionExecutionStartEvent) {
        val actionName = event.action.name
        val friendlyLabel = mapActionToLabel(actionName)
        if (friendlyLabel != null) {
            // We publish a ProgressUpdateEvent using the label, which Embabel uses for SSE updates.
            // 0 out of 0 means indeterminate progress, which is typically good for status text updates.
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, friendlyLabel, 0, 0))
        }
    }

    @EventListener
    fun onSnapshot(event: BpmnSnapshotEvent) {
        val label = when (event.stage) {
            "INITIAL_RENDER" -> "Rendered BPMN XML"
            "VALIDATION_FAILED" -> {
                val base = "Validating and repairing (Attempt ${event.attemptNumber ?: 1})"
                val counts = listOfNotNull(
                    if (event.graphIssues > 0) "${event.graphIssues} graph" else null,
                    if (event.xsdIssues > 0) "${event.xsdIssues} XSD" else null,
                    if (event.lintIssues > 0) "${event.lintIssues} lint" else null,
                )
                if (counts.isNotEmpty()) {
                    "$base: ${counts.joinToString(", ")} issues"
                } else {
                    base
                }
            }
            "FINAL_VALIDATION" -> "Final validation"
            else -> null
        }

        if (label != null) {
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, label, 0, 0))
        }
    }

    private fun mapActionToLabel(actionName: String): String? = when (actionName) {
        "createProcessOutline" -> "Generating BPMN structure"
        "validateOutline" -> "Validating outline"
        "generatePhasePlans" -> "Planning phase details" // not explicitly in ticket list but makes sense
        "composeProcessGraph" -> "Composing process graph"
        "assignOwnership" -> "Applying XML fixes" // approximation based on what it does
        "assignLayout" -> "Laying out diagram"
        "renderBpmnXml" -> "Rendering BPMN XML"
        "refine" -> "Validating and repairing"
        "finalizeBpmn" -> "Finalizing BPMN output"
        else -> null // Unknown events do not create noisy entries
    }
}
