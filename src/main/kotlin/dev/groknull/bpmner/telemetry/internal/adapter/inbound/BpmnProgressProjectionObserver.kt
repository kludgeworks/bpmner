/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

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

    private fun mapActionToLabel(actionName: String): String? = ACTION_LABELS[actionName]

    companion object {
        // Maps every `@Action` method name in the codebase to a user-facing progress string. Keep
        // this exhaustive — silent gaps (a missing entry) mean the UI stalls on "unknown step"
        // for the duration of an action's execution. If you add a new `@Action`, add it here.
        private val ACTION_LABELS: Map<String, String> = mapOf(
            // Input side (BpmnReadinessAgent + BpmnContractAgent)
            "assessReadiness" to "Assessing input readiness",
            "extractProcessContract" to "Extracting process contract",
            // Generator (BpmnGeneratorAgent, Phase 5, 4 actions)
            "createOutline" to "Generating BPMN structure",
            "composeGraph" to "Composing process graph",
            "renderBpmnXml" to "Rendering BPMN XML",
            "finalizeBpmn" to "Finalizing BPMN output",
            // Repair (BpmnRepairAgent, Phase 4 GOAP, 5 actions + finalize)
            "validate" to "Validating BPMN",
            "applyDeterministicFixes" to "Applying deterministic fixes",
            "applyLlmLabelPatch" to "Repairing labels",
            "applyLlmStructuralPatch" to "Repairing structure",
            "applyFullLlmRewrite" to "Rewriting BPMN",
            "finalize" to "Finalizing repair",
            // Layout (BpmnLayoutAgent)
            "layoutBpmnXml" to "Laying out diagram",
            "autoFixBpmnXml" to "Auto-fixing diagram XML",
            "validateFinalBpmnXml" to "Validating final BPMN",
            // Alignment (BpmnAlignmentAgent)
            "checkAlignment" to "Verifying semantic alignment",
            // Rule evaluation (LlmRuleAgent — called from inside the repair loop)
            "evaluateLlmRules" to "Evaluating LLM rules",
            // State-machine flow transitions
            "proceed" to "Proceeding with generation",
            "terminate" to "Terminating process",
        )
    }
}
