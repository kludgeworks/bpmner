/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.ProgressUpdateEvent
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
        val label =
            when (event.stage) {
                "INITIAL_RENDER" -> {
                    "Rendered BPMN XML"
                }

                "VALIDATION_FAILED" -> {
                    val base = "Validating and repairing (Attempt ${event.attemptNumber ?: 1})"
                    val counts =
                        listOfNotNull(
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

                "FINAL_VALIDATION" -> {
                    "Final validation"
                }

                else -> {
                    null
                }
            }

        if (label != null) {
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, label, 0, 0))
        }
    }

    private fun mapActionToLabel(actionName: String): String? = when (actionName) {
        // Generator (Phase 5, 4 actions)
        "createOutline" -> "Generating BPMN structure"

        "composeGraph" -> "Composing process graph"

        "renderBpmnXml" -> "Rendering BPMN XML"

        "finalizeBpmn" -> "Finalizing BPMN output"

        // Repair (Phase 4 GOAP, 5 actions + finalize)
        "validate" -> "Validating BPMN"

        "applyDeterministicFixes" -> "Applying deterministic fixes"

        "applyLlmLabelPatch" -> "Repairing labels"

        "applyLlmStructuralPatch" -> "Repairing structure"

        "applyFullLlmRewrite" -> "Rewriting BPMN"

        "finalize" -> "Finalizing repair"

        else -> null // Unknown events do not create noisy entries
    }
}
