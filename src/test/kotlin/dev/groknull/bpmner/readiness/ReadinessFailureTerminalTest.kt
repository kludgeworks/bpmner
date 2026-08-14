/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.pipeline.RunOutcome
import dev.groknull.bpmner.pipeline.RunUpdate
import dev.groknull.bpmner.pipeline.internal.adapter.inbound.RunUpdateSinkRegistry
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Readiness is the one fallible stage with no typed terminal of its own, so the two properties
 * that decision rests on are pinned here.
 *
 * An action's effect is the type it produces, and the planner drops actions whose effect already
 * holds. `assessReadiness` therefore has to keep returning [ProcessInputAssessment]: any wrapper
 * type — including a sealed ready/failed pair like the other stages have — leaves that effect
 * permanently unsatisfied, and readiness runs even when the caller already supplied an assessment.
 * That costs a model call per run and is silent, so it is asserted rather than assumed.
 *
 * Because readiness cannot carry its own terminal, a readiness failure reaches the author only via
 * the run-aborted backstop. That path crosses a sub-process boundary, the outer process's run loop
 * and a background future, so it is asserted end to end rather than at the listener alone.
 */
@TestPropertySource(
    properties = [
        "embabel.agent.platform.models.anthropic.api-key=test-key",
        "embabel.agent.platform.models.openai.api-key=test-key",
        "embabel.agent.platform.models.gemini.api-key=test-key",
        "embabel.agent.platform.models.mistralai.api-key=test-key",
        "embabel.agent.platform.models.deepseek.api-key=test-key",
    ],
)
class ReadinessFailureTerminalTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Autowired
    private lateinit var registry: RunUpdateSinkRegistry

    @MockitoSpyBean
    private lateinit var readinessInvoker: BpmnReadinessInvoker

    @Test
    fun `a supplied assessment is used instead of running readiness again`() {
        // The property that costs readiness its typed terminal. If a future change gives
        // assessReadiness a wrapper return type, the planner stops pruning it and this fails.
        whenCreateObject({ true }, ProcessInputAssessment::class.java)
            .thenThrow(IllegalStateException("readiness must not be called when an assessment is supplied"))

        runCatching {
            bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())
        }

        verify(readinessInvoker, never()).assess(anyNonNull())
    }

    @Test
    fun `a readiness failure still ends the run with a terminal and a reason`() {
        // No assessment supplied, so readiness runs — and fails to produce a parseable one.
        // doThrow/when form: the plain when(spy.call()) form would invoke the real method.
        doThrow(BpmnReadinessAssessmentException("readiness model returned nothing usable"))
            .`when`(readinessInvoker).assess(anyNonNull())

        val processId = bpmnAgentInvoker.startAsync(BpmnRequest(processDescription = READY_PROSE))

        // The failure escapes the process run loop, so it surfaces through the background future
        // rather than any lifecycle event; collectList completes once the sink is terminated.
        val updates = registry.subscribe(processId).collectList().block(TIMEOUT)!!

        val terminal = updates.filterIsInstance<RunUpdate.Terminal>().singleOrNull()
        assertNotNull(terminal, "a failed run must still produce exactly one terminal: $updates")
        assertEquals(RunOutcome.FAILED, terminal.outcome)
        assertTrue(
            terminal.detail["failureDetail"]?.contains("nothing usable") == true,
            "terminal must say why the run stopped, got: ${terminal.detail}",
        )
    }

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        // Mockito's any() returns null at runtime; the cast is the canonical Kotlin-Mockito
        // bridge and is safe inside a stubbing or verification call.
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun readyAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK")),
        evidence = listOf(SourceEvidence("ev1", "Unused")),
        rationale = "Ready",
    )

    private companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val READY_PROSE =
            "When a user submits an order, we process it and then it is completed."
    }
}
