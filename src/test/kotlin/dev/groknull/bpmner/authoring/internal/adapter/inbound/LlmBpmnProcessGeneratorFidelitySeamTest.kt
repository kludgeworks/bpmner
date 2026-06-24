/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.core.Retryable
import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.authoring.BpmnAgentInvoker
import dev.groknull.bpmner.authoring.BpmnContractFidelityPort
import dev.groknull.bpmner.authoring.BpmnDefaultFlowPort
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
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.conformance.BpmnLoggingConfig
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.ContractValidationIssue
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Focused test for the #458 fidelity-to-exception seam in [LlmBpmnProcessGenerator.createOutline].
 *
 * Architecture §5 "fidelity-seam test" (R6): proves that ERROR-severity fidelity results are
 * converted to [RetryableBpmnGenerationException] with the violation count and per-issue
 * `- [code] message` body preserved in the exception message (ADR-004: message IS the feedback).
 *
 * [LlmBpmnProcessGeneratorTest] remains @Disabled; this test covers the seam via
 * [FakeOperationContext] + a mocked [BpmnContractFidelityPort] so the LLM path is bypassed
 * entirely, as permitted by PLAN §1.3 / §5 R6.
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
    private val mockDefaultFlowAssigner = mock(BpmnDefaultFlowPort::class.java)

    private val generator = LlmBpmnProcessGenerator(
        config = BpmnAuthoringConfig(),
        logging = BpmnLoggingConfig(),
        metricsCalculator = BpmnGeneratorMetrics(),
        fidelityChecker = mockFidelityChecker,
        defaultFlowAssigner = mockDefaultFlowAssigner,
        contractRenderer = ProcessContractMarkdownRenderer(),
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

    // Minimal valid contract — just enough for the isValid check and fidelityChecker.check() args.
    private val contract = ProcessContract(
        id = "contract-seam",
        processName = "Seam test",
        summary = "Seam test contract for fidelity exception coverage",
        trigger = "An order is received",
        activities = listOf(ContractActivity.Service("act1", "Do work")),
        endStates = listOf(ContractEndState.Normal("end1", "Done")),
    )

    private val validatedContract = ValidatedProcessContract(
        contract = contract,
        report = ContractValidationReport(issues = emptyList()),
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

    // --- #458 fidelity seam (R6) ---

    @Test
    fun `createOutline converts ERROR fidelity result to RetryableBpmnGenerationException`() {
        // #458 fidelity seam (R6): ERROR-severity fidelity result → RetryableBpmnGenerationException.
        // The exception message must preserve the violation count and per-issue [code] message body
        // so the repair loop has the feedback it needs (ADR-004).
        val context = FakeOperationContext()
        context.expectResponse(flatLlmResponse)

        // Use any() matcher since the BpmnDefinition instance created inside createOutline is
        // a different object from flatLlmResponse.toSealed() (even if structurally equal).
        val stubbedDefinition = flatLlmResponse.toSealed()
        `when`(mockDefaultFlowAssigner.assign(anyKt(), anyKt())).thenReturn(stubbedDefinition)

        val errorReport = BpmnFidelityReport(
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
        `when`(mockFidelityChecker.checkDetailed(anyKt(), anyKt()))
            .thenReturn(errorReport)

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            generator.createOutline(ready, validatedContract, context)
        }

        // Exception must implement Retryable (EG3 / R7 marker).
        assertIs<Retryable>(ex)

        // Message must carry the total issue count (R6 — count preserved).
        assertContains(ex.message!!, "2 fidelity issue(s)")

        // Message must carry each violation's code and body (R6 / ADR-004 — feedback body preserved).
        assertContains(ex.message!!, "- [ACTIVITY_TASK_KIND_MISMATCH] Activity 'act1' is a ServiceTask")
        assertContains(ex.message!!, "- [DECISION_GATEWAY_MISSING] Decision 'dec1' has no corresponding")
    }

    // --- R2 regression guard: kept preconditions throw non-retryable error ---

    @Test
    fun `RuleCategory unknown name throws non-retryable IllegalStateException`() {
        // ADR-002 keep-list: RuleCategory.kt:34 — internal enum-resolution precondition must NOT
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

    @Test
    fun `invalid process contract throws non-retryable error (LlmBpmnProcessGenerator line 75)`() {
        // ARCHITECTURE §4 "Do NOT convert" / ADR-002 keep-list:
        // LlmBpmnProcessGenerator.createOutline line 75 — invalid-contract precondition must NOT
        // be retryable. Retrying an invalid contract burns attempts without benefit (R2).
        val context = FakeOperationContext()
        val invalidReport = ContractValidationReport(
            issues = listOf(
                ContractValidationIssue(
                    code = ContractValidationCode.MISSING_TRIGGER,
                    severity = ContractIssueSeverity.ERROR,
                    message = "Process contract has no trigger",
                ),
            ),
        )
        val invalidContract = ValidatedProcessContract(
            contract = contract,
            report = invalidReport,
        )

        val ex = assertFailsWith<IllegalStateException> {
            generator.createOutline(ready, invalidContract, context)
        }

        // Must NOT be RetryableBpmnGenerationException — kept precondition is non-retryable (R2).
        assertFalse(
            ex is RetryableBpmnGenerationException,
            "Precondition error must not be RetryableBpmnGenerationException",
        )
    }
}
