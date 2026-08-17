/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.core.NonRetryable
import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.authoring.BpmnAgentInvoker
import dev.groknull.bpmner.authoring.BpmnConformance
import dev.groknull.bpmner.authoring.BpmnContractConformancePort
import dev.groknull.bpmner.authoring.BpmnContractFidelityPort
import dev.groknull.bpmner.authoring.BpmnOutlineGenerationException
import dev.groknull.bpmner.authoring.BpmnRenderer
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringConfig
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNode
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNodeKind
import dev.groknull.bpmner.authoring.internal.adapter.outbound.toSealed
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityCode
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityIssue
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityReport
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelitySeverity
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.conformance.BpmnLoggingConfig
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.llm.PromptJsonRenderer
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.module.kotlin.jsonMapper
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Covers what [LlmBpmnProcessGenerator.createOutline] does when the generated definition does not
 * faithfully encode the contract: it re-asks the model with the fidelity diagnostic in the prompt,
 * and fails non-retryably once that budget is spent.
 *
 * Drives the seam through [FakeOperationContext] plus a mocked [BpmnContractFidelityPort], so the
 * assertions are about the prompts the generator builds rather than about any model's behaviour.
 */
/**
 * Kotlin-safe wrapper for Mockito.any() that avoids NPE on non-null Kotlin parameters.
 * Mockito.any() returns null in Java, which violates Kotlin's non-null contract.
 */
private fun <T> anyKt(): T {
    Mockito.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

@Suppress("TooManyFunctions")
class LlmBpmnProcessGeneratorFidelitySeamTest {
    private val mockFidelityChecker = mock(BpmnContractFidelityPort::class.java)
    private val mockConformancePort = mock(BpmnContractConformancePort::class.java)

    private val generator = LlmBpmnProcessGenerator(
        config = BpmnAuthoringConfig(),
        logging = BpmnLoggingConfig(),
        metricsCalculator = BpmnGeneratorMetrics(),
        fidelityChecker = mockFidelityChecker,
        conformancePort = mockConformancePort,
        jsonRenderer = PromptJsonRenderer(jsonMapper()),
        renderer = mock(BpmnRenderer::class.java),
        agentInvoker = mock(BpmnAgentInvoker::class.java),
        eventPublisher = mock(ApplicationEventPublisher::class.java),
    )

    // Minimal FlatBpmnDefinition returned by the fake LLM — just enough to pass toSealed().
    private val flatLlmResponse = FlatBpmnDefinition(
        processId = "Process_seam",
        processName = "Seam test",
        nodes = listOf(
            FlatBpmnNode(id = "start", type = FlatBpmnNodeKind.START_EVENT, name = "Start"),
            FlatBpmnNode(id = "act1", type = FlatBpmnNodeKind.SERVICE_TASK, name = "Do work"),
            FlatBpmnNode(id = "end", type = FlatBpmnNodeKind.END_EVENT, name = "Done"),
        ),
        sequences = listOf(
            BpmnEdge("f1", "start", "act1"),
            BpmnEdge("f2", "act1", "end"),
        ),
    )

    // Minimal valid contract — just enough for fidelityChecker.check() args.
    private val contract = ProcessContract(
        id = "contract-seam",
        processName = "Seam test",
        summary = "Seam test contract for fidelity exception coverage",
        start = ContractStart(ContractTrigger.None("An order is received")),
        activities = listOf(ContractActivity.Service("act1", "Do work")),
        endStates = listOf(ContractEndState.Normal("end1", "Done")),
    )

    private val validatedContract = ValidatedProcessContract.of(
        contract = contract,
        report = ContractValidationReport(issues = emptyList()),
    )!!

    private val errorReport = BpmnFidelityReport(
        issues = listOf(
            BpmnFidelityIssue(
                code = BpmnFidelityCode.ACTIVITY_TASK_KIND_MISMATCH,
                severity = BpmnFidelitySeverity.ERROR,
                message = "Activity 'act1' is a ServiceTask but contract requires UserTask",
                bpmnElementId = "act1",
            ),
            BpmnFidelityIssue(
                code = BpmnFidelityCode.DECISION_GATEWAY_MISSING,
                severity = BpmnFidelitySeverity.ERROR,
                message = "Decision 'dec1' has no corresponding gateway in the BPMN",
                bpmnElementId = "dec1",
            ),
        ),
    )

    private val ready = ReadyBpmnContext(
        request = BpmnRequest("Order processing seam test"),
        assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 90,
            dimensions = ReadinessDimension.entries.map {
                ReadinessDimensionScore(dimension = it, score = 90, rationale = "ok")
            },
            rationale = "Seam test",
        ),
    )

    // --- fidelity seam: correction, not re-rolling ---

    @Test
    fun `fidelity failure makes the next prompt state what was wrong`() {
        // The point of retrying is that the model is told what it got wrong. A retry whose prompt
        // is byte-identical to its predecessor is a re-roll, not a correction, so assert the
        // difference directly rather than inferring it from an eventual success.
        val context = FakeOperationContext()
        context.expectResponse(flatLlmResponse)
        context.expectResponse(flatLlmResponse)

        val stubbedDefinition = flatLlmResponse.toSealed()
        `when`(mockConformancePort.conform(anyKt(), anyKt())).thenReturn(BpmnConformance(stubbedDefinition, emptyList()))
        `when`(mockFidelityChecker.checkDetailed(anyKt(), anyKt()))
            .thenReturn(errorReport)
            .thenReturn(BpmnFidelityReport(issues = emptyList()))

        generator.createOutline(ready, validatedContract, context)

        assertEquals(2, context.llmInvocations.size, "second attempt should have been made")
        val first = context.llmInvocations[0].prompt
        val second = context.llmInvocations[1].prompt
        assertNotEquals(first, second, "retry prompt must differ from the attempt it is correcting")

        // The diagnostic itself must reach the model, not just a generic 'try again'.
        assertContains(second, "ACTIVITY_TASK_KIND_MISMATCH")
        assertContains(second, "Activity 'act1' is a ServiceTask but contract requires UserTask")
        assertContains(second, "DECISION_GATEWAY_MISSING")

        // The first attempt has nothing to correct, so it must not carry the feedback block.
        assertFalse(
            first.contains("ACTIVITY_TASK_KIND_MISMATCH"),
            "first attempt must not claim a previous failure",
        )
    }

    @Test
    fun `outline generation fails non-retryably once the correction budget is spent`() {
        // Exhausting the budget must surface a typed failure the planner can route to a terminal.
        // A Retryable exception here would hand the whole loop back to the framework, which would
        // re-run it from scratch with no diagnostic — the stochastic retry this loop replaced.
        val context = FakeOperationContext()
        repeat(DEFAULT_ATTEMPTS) { context.expectResponse(flatLlmResponse) }

        val stubbedDefinition = flatLlmResponse.toSealed()
        `when`(mockConformancePort.conform(anyKt(), anyKt())).thenReturn(BpmnConformance(stubbedDefinition, emptyList()))
        `when`(mockFidelityChecker.checkDetailed(anyKt(), anyKt())).thenReturn(errorReport)

        val ex = assertFailsWith<BpmnOutlineGenerationException> {
            generator.createOutline(ready, validatedContract, context)
        }

        assertIs<NonRetryable>(ex)
        assertEquals(DEFAULT_ATTEMPTS, context.llmInvocations.size, "must respect the attempt budget")

        // The reason must survive into the terminal, or the run reports a failure with no cause.
        assertContains(ex.message!!, "- [ACTIVITY_TASK_KIND_MISMATCH] Activity 'act1' is a ServiceTask")
        assertContains(ex.message!!, "- [DECISION_GATEWAY_MISSING] Decision 'dec1' has no corresponding")
    }

    @Test
    fun `conform runs before the fidelity check, over the conformance-corrected definition`() {
        // Gate 6: the checks conformance supersedes must run over the post-conformance artifact,
        // so a future refactor that reorders these two calls (or feeds the fidelity checker the
        // model's raw, unstamped output) must fail this test.
        val context = FakeOperationContext()
        context.expectResponse(flatLlmResponse)

        val rawDefinition = flatLlmResponse.toSealed()
        val stampedDefinition = rawDefinition.copy(processName = "Stamped by conformance")
        `when`(mockConformancePort.conform(anyKt(), anyKt())).thenReturn(BpmnConformance(stampedDefinition, emptyList()))
        var fidelityCheckSawDefinition: BpmnDefinition? = null
        `when`(mockFidelityChecker.checkDetailed(anyKt(), anyKt())).thenAnswer { invocation ->
            fidelityCheckSawDefinition = invocation.getArgument(1)
            BpmnFidelityReport(issues = emptyList())
        }

        generator.createOutline(ready, validatedContract, context)

        val order = Mockito.inOrder(mockConformancePort, mockFidelityChecker)
        order.verify(mockConformancePort).conform(anyKt(), anyKt())
        order.verify(mockFidelityChecker).checkDetailed(anyKt(), anyKt())

        assertEquals(
            stampedDefinition,
            fidelityCheckSawDefinition,
            "fidelity check must run over the conformance-corrected definition, not the model's raw output",
        )
    }

    // --- R2 regression guard: kept preconditions throw non-retryable error ---

    @Test
    fun `RuleCategory unknown name throws non-retryable IllegalStateException`() {
        // RuleCategory.kt:34 — internal enum-resolution precondition must NOT
        // be RetryableBpmnGenerationException; it is not an LLM-output failure, so retrying is useless.
        val ex = assertFailsWith<IllegalStateException> {
            dev.groknull.bpmner.bpmn.RuleCategory.fromDisplayName("no_such_category")
        }
        assertFalse(
            ex is RetryableBpmnGenerationException,
            "RuleCategory precondition must not be RetryableBpmnGenerationException",
        )
        assertContains(ex.message!!, "Unknown rule category")
    }

    private companion object {
        // Mirrors BpmnAuthoringConfig.maxOutlineAttempts, which this test leaves at its default.
        const val DEFAULT_ATTEMPTS = 3
    }
}
