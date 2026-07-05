/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.layout.internal.adapter.inbound.BpmnLayoutAgent
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.BpmnGenerationAgent
import dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessAgent
import dev.groknull.bpmner.telemetry.BpmnSnapshotEvent
import dev.groknull.bpmner.telemetry.BpmnStageEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher

class BpmnProgressProjectionObserverTest {
    // Recording fake publisher — captures published events in order.
    private val published = mutableListOf<Any>()
    private val publisher = ApplicationEventPublisher { published.add(it) }
    private val observer = BpmnProgressProjectionObserver(publisher)

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun actionEvent(name: String): ActionExecutionStartEvent {
        val action = mock(com.embabel.agent.core.Action::class.java)
        `when`(action.name).thenReturn(name)
        val process = mock(AgentProcess::class.java)
        return ActionExecutionStartEvent(process, action)
    }

    private fun stageEvents(): List<BpmnStageEvent> = published.filterIsInstance<BpmnStageEvent>()

    // -------------------------------------------------------------------------
    // Drift guard — fails the build if ACTION_LABELS or ACTION_STAGES reference
    // a key that is no longer a live @Action method name. Run this first.
    // -------------------------------------------------------------------------

    @Test
    fun `ACTION_LABELS and ACTION_STAGES keys are all live @Action method names`() {
        val agentClasses =
            listOf(
                BpmnGenerationAgent::class.java,
                BpmnReadinessAgent::class.java,
                BpmnLayoutAgent::class.java,
            )

        // Collect @Action method names from each agent class. State-machine action
        // methods (proceed, assess, ask, terminate) live on top-level @State classes
        // in the same file, not on inner/nested classes of the agent — and none of
        // those names appear in ACTION_LABELS or ACTION_STAGES, so we do not need to
        // scan for them here.
        val liveActionNames: Set<String> =
            agentClasses.flatMap { clazz ->
                clazz.declaredMethods.filter { it.isAnnotationPresent(Action::class.java) }.map { it.name }
            }.toSet()

        val deadLabels = BpmnProgressProjectionObserver.ACTION_LABELS.keys - liveActionNames
        assertTrue(
            deadLabels.isEmpty(),
            "ACTION_LABELS contains keys with no matching @Action method — stale entries: $deadLabels. " +
                "Live @Action names: $liveActionNames",
        )

        val deadStages = BpmnProgressProjectionObserver.ACTION_STAGES.keys - liveActionNames
        assertTrue(
            deadStages.isEmpty(),
            "ACTION_STAGES contains keys with no matching @Action method — stale entries: $deadStages. " +
                "Live @Action names: $liveActionNames",
        )
    }

    // -------------------------------------------------------------------------
    // Happy-path sequence
    // -------------------------------------------------------------------------

    @Test
    fun `happy-path action sequence emits correct stage events in order`() {
        // Main path: readiness → contract → generate (×3) → validate → layout → align → (align, done)
        val actions = listOf(
            "draft", "resolve", // unmapped — emit nothing
            "assessReadiness", // → (readiness, active)
            "startAssessing", // unmapped
            "proceed", // unmapped (state transition)
            "extractContract", // → (contract, active)
            "createOutline", // → (generate, active)
            "composeGraph", // → (generate, active)
            "render", // → (generate, active)
            "validate", // → (validate, active)
            "proceed", // unmapped
            "layout", // → (layout, active)
            "align", // → (align, active)
            "finish", // → (align, done)
        )
        actions.forEach { observer.onActionStart(actionEvent(it)) }

        val stages = stageEvents()
        assertEquals(
            listOf(
                "readiness" to "active",
                "contract" to "active",
                "generate" to "active",
                "generate" to "active",
                "generate" to "active",
                "validate" to "active",
                "layout" to "active",
                "align" to "active",
                "align" to "done",
            ),
            stages.map { it.stage to it.stageStatus },
        )
    }

    // -------------------------------------------------------------------------
    // Warn from VALIDATION_FAILED snapshot
    // -------------------------------------------------------------------------

    @Test
    fun `VALIDATION_FAILED snapshot emits (validate, warn) stage event`() {
        val process = mock(AgentProcess::class.java)
        val snapshot =
            BpmnSnapshotEvent(
                process = process,
                stage = "VALIDATION_FAILED",
                attemptNumber = 1,
                xml = "<definitions/>",
            )
        observer.onSnapshot(snapshot)

        val stages = stageEvents()
        assertEquals(1, stages.size)
        assertEquals("validate", stages[0].stage)
        assertEquals("warn", stages[0].stageStatus)
    }

    // -------------------------------------------------------------------------
    // No-op for unmapped action names
    // -------------------------------------------------------------------------

    @Test
    fun `unmapped action names emit no stage event`() {
        listOf("ask", "startAssessing", "assess", "proceed", "terminate", "resolve", "draft", "unknownFuture")
            .forEach { observer.onActionStart(actionEvent(it)) }

        assertTrue(stageEvents().isEmpty(), "Expected no stage events for unmapped actions")
    }

    // -------------------------------------------------------------------------
    // Vague-input path — stops at readiness active
    // -------------------------------------------------------------------------

    @Test
    fun `vague-input path stops at readiness active`() {
        listOf("draft", "resolve", "assessReadiness", "startAssessing", "ask").forEach {
            observer.onActionStart(actionEvent(it))
        }

        val stages = stageEvents()
        // Only assessReadiness maps; rest are unmapped
        assertEquals(1, stages.size)
        assertEquals("readiness", stages[0].stage)
        assertEquals("active", stages[0].stageStatus)
    }
}
