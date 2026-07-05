/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ProgressUpdateEvent
import dev.groknull.bpmner.telemetry.BpmnSnapshotEvent
import dev.groknull.bpmner.telemetry.BpmnStageEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class BpmnProgressProjectionObserver(
    private val eventPublisher: ApplicationEventPublisher,
) : AgenticEventListener {

    // ActionExecutionStartEvent is emitted on Embabel's agentic event bus, not Spring's
    // ApplicationEventPublisher — so this observer must be an AgenticEventListener (auto-registered
    // globally on the platform) to see it. A plain @EventListener would never fire for engine
    // events. Snapshot handling below stays on @EventListener because BpmnSnapshotEvent is
    // published via ApplicationEventPublisher.
    override fun onProcessEvent(event: AgentProcessEvent) {
        if (event is ActionExecutionStartEvent) {
            onActionStart(event)
        }
    }

    fun onActionStart(event: ActionExecutionStartEvent) {
        // Embabel's core engine uses fully qualified action names (e.g., "dev.groknull...BpmnGenerationAgent.assessReadiness"),
        // but our mapping tables use the bare method name.
        val actionName = event.action.name.substringAfterLast(".")
        val friendlyLabel = mapActionToLabel(actionName)
        if (friendlyLabel != null) {
            // We publish a ProgressUpdateEvent using the label, which Embabel uses for SSE updates.
            // Using 0 out of 1 to avoid / by zero in Embabel's logging listener.
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, friendlyLabel, 0, 1))
        }
        val stageMapping = ACTION_STAGES[actionName] ?: return
        val label = friendlyLabel ?: actionName
        eventPublisher.publishEvent(BpmnStageEvent(event.agentProcess, stageMapping.stage, stageMapping.stageStatus, label))
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
            eventPublisher.publishEvent(ProgressUpdateEvent(event.agentProcess, label, 0, 1))
        }

        // Emit a typed warn stage event for repair attempts so the rail highlights the validate chip.
        if (event.stage == "VALIDATION_FAILED") {
            val warnLabel = label ?: "Validating and repairing (Attempt ${event.attemptNumber ?: 1})"
            eventPublisher.publishEvent(
                BpmnStageEvent(event.agentProcess, stage = "validate", stageStatus = "warn", label = warnLabel),
            )
        }
    }

    private fun mapActionToLabel(actionName: String): String? = ACTION_LABELS[actionName]

    companion object {
        // Maps every @Action method name in the codebase to a user-facing progress string. Keep
        // this exhaustive — silent gaps (a missing entry) mean the UI stalls on "unknown step"
        // for the duration of an action's execution. If you add a new @Action, add it here.
        //
        // Keys are the RUNTIME action names (= bare Kotlin method names; no @Action(name=…)
        // overrides exist in this codebase). BpmnProgressProjectionObserverTest enforces this
        // via reflection so stale keys fail the build.
        @JvmField
        internal val ACTION_LABELS: Map<String, String> = mapOf(
            // BpmnReadinessAgent
            "assessReadiness" to "Assessing input readiness",
            // BpmnGenerationAgent — contract, generation, validate, layout, align, finish
            "extractContract" to "Extracting process contract",
            "createOutline" to "Generating BPMN structure",
            "composeGraph" to "Composing process graph",
            "render" to "Rendering BPMN XML",
            "validate" to "Validating BPMN",
            "layout" to "Laying out diagram",
            "align" to "Verifying semantic alignment",
            "finish" to "Finalizing BPMN output",
            // BpmnLayoutAgent (standalone layout path)
            "layoutBpmnXml" to "Laying out diagram",
            "validateFinalBpmnXml" to "Validating final BPMN",
        )

        // Maps runtime action names to (stage, status) for the six-chip pipeline rail.
        // Stage keys: readiness | contract | generate | validate | layout | align
        // Status: active | done | warn  (warn is set from VALIDATION_FAILED snapshot, not here)
        @JvmField
        internal val ACTION_STAGES: Map<String, StageMapping> = mapOf(
            "assessReadiness" to StageMapping(stage = "readiness", stageStatus = "active"),
            "reassess" to StageMapping(stage = "readiness", stageStatus = "active"),
            "extractContract" to StageMapping(stage = "contract", stageStatus = "active"),
            "createOutline" to StageMapping(stage = "generate", stageStatus = "active"),
            "composeGraph" to StageMapping(stage = "generate", stageStatus = "active"),
            "render" to StageMapping(stage = "generate", stageStatus = "active"),
            "validate" to StageMapping(stage = "validate", stageStatus = "active"),
            "layout" to StageMapping(stage = "layout", stageStatus = "active"),
            "layoutBpmnXml" to StageMapping(stage = "layout", stageStatus = "active"),
            "align" to StageMapping(stage = "align", stageStatus = "active"),
            "finish" to StageMapping(stage = "align", stageStatus = "done"),
        )
    }
}

/** Lightweight value type for the action→stage mapping table. */
internal data class StageMapping(val stage: String, val stageStatus: String)
