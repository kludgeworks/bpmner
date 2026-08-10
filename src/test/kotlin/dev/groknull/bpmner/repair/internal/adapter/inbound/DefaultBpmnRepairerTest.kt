/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.common.ActionContext
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnValidationFailedEvent
import dev.groknull.bpmner.conformance.BpmnValidationPassedEvent
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairAdvancer
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairEvaluation
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairLoop
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test

/**
 * Regression guard: the pipeline's own gate for "did validation succeed" must be whether any
 * *blocking* (ERROR) diagnostic remains, not whether the raw diagnostics list is empty. An
 * advisory-only outcome — the exact shape [DefaultBpmnRepairer]'s "Pipeline succeeded... N
 * advisory diagnostic(s) remaining" log line describes — must publish [BpmnValidationPassedEvent],
 * not [BpmnValidationFailedEvent], for the same run.
 */
class DefaultBpmnRepairerTest {
    private val advancer = mock(BpmnRepairAdvancer::class.java)
    private val repairLoop = mock(BpmnRepairLoop::class.java)
    private val eventPublisher = mock(ApplicationEventPublisher::class.java)
    private val repairer = DefaultBpmnRepairer(advancer, repairLoop, eventPublisher)

    private val ready = mock(ReadyBpmnContext::class.java)
    private val graph = mock(LaidOutProcessGraph::class.java)
    private val rendered = mock(RenderedBpmn::class.java)
    private val contract = mock(ValidatedProcessContract::class.java)
    private val context = mock(ActionContext::class.java)

    @Test
    fun `advisory-only diagnostics publish a validation-passed event, not failed`() {
        val advisory = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "consider naming this flow",
            severity = BpmnDiagnosticSeverity.WARNING,
        )
        stub(diagnostics = listOf(advisory))

        repairer.validateInitial(ready, graph, rendered, contract, context)

        verify(eventPublisher).publishEvent(any(BpmnValidationPassedEvent::class.java))
    }

    @Test
    fun `a blocking diagnostic still publishes a validation-failed event`() {
        val blocking = BpmnDiagnostic(
            source = BpmnDiagnosticSource.LINT,
            message = "unresolved outcome label",
            severity = BpmnDiagnosticSeverity.ERROR,
        )
        stub(diagnostics = listOf(blocking))

        repairer.validateInitial(ready, graph, rendered, contract, context)

        verify(eventPublisher).publishEvent(any(BpmnValidationFailedEvent::class.java))
    }

    private fun stub(diagnostics: List<BpmnDiagnostic>) {
        val evaluation = BpmnEvaluation(
            definition = mock(BpmnDefinition::class.java),
            rendered = rendered,
            diagnostics = diagnostics,
            globalDiagnostics = GlobalDiagnostics(diagnostics),
            validatedXml = "<xml/>",
        )
        val repairEval = BpmnRepairEvaluation(
            request = BpmnRequest(processDescription = "test"),
            graph = graph,
            rendered = rendered,
            evaluation = evaluation,
            messages = emptyList(),
            history = BpmnAttemptHistory(),
            contract = mock(ProcessContract::class.java),
            repairAttempts = 1,
        )
        `when`(advancer.initialEvaluation(ready, graph, rendered, contract)).thenReturn(repairEval)
        `when`(repairLoop.run(repairEval, context)).thenReturn(repairEval)
    }
}
