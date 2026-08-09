/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.BpmnOutlineGenerationException
import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNode
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNodeKind
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.readiness.EvidenceSourceType
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.SourceEvidence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Regression guard for outline generation's structured-output reliability: the framework's own
 * `InvalidLlmReturn*` failures — raised directly from the LLM call that produces the raw
 * [FlatBpmnDefinition], before `flat.toSealed()` runs — must end the run at an `OUTLINE_FAILED`
 * terminal carrying a reason, and the underlying failure must stay [NonRetryable].
 *
 * The existing, untouched `toSealed()`-incompleteness path (a manufactured
 * `InvalidLlmReturnFormatException`, re-thrown to keep the planner's outline-retry engaged) must
 * keep surfacing as that framework exception — the two failure shapes must stay structurally
 * distinct.
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
class LlmBpmnProcessGeneratorReliabilityTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `malformed generator output ends the run at an OUTLINE_FAILED terminal`() {
        mockValidContract()
        val formatFailure = InvalidLlmReturnFormatException(
            "not json",
            FlatBpmnDefinition::class.java,
            RuntimeException("malformed"),
        )
        whenCreateObject({ true }, FlatBpmnDefinition::class.java).thenThrow(formatFailure)

        val result = bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())

        assertEquals(BpmnGenerationStatus.OUTLINE_FAILED, result.status)
        assertNotNull(result.failureDetail, "terminal must carry why the run stopped")
    }

    @Test
    fun `invalid generator output ends the run at an OUTLINE_FAILED terminal`() {
        mockValidContract()
        val typeFailure = InvalidLlmReturnTypeException(returnedObject = "not-a-definition", constraintViolations = emptySet())
        whenCreateObject({ true }, FlatBpmnDefinition::class.java).thenThrow(typeFailure)

        val result = bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())

        assertEquals(BpmnGenerationStatus.OUTLINE_FAILED, result.status)
        assertNotNull(result.failureDetail, "terminal must carry why the run stopped")
    }

    @Test
    fun `outline generation failure stays non-retryable`() {
        // The framework retries any exception it is not told is permanent, and outline generation
        // already spends its own correction budget before failing.
        val outlineFailure = BpmnOutlineGenerationException("boom")
        assertIs<NonRetryable>(outlineFailure)
    }

    /**
     * Regression guard for the "don't touch the legitimate retry path" design decision: a
     * structurally incomplete node (BUSINESS_RULE_TASK with no `decisionRef`) that parses fine as
     * JSON but fails `toSealed()` must still surface as the framework's own
     * `InvalidLlmReturnFormatException` — not [BpmnOutlineGenerationException] — so the planner's
     * outline-retry path keeps engaging unchanged.
     */
    @Test
    fun `a structurally incomplete node still surfaces the framework's InvalidLlmReturnFormatException unchanged`() {
        mockValidContract()
        whenCreateObject({ true }, FlatBpmnDefinition::class.java).thenReturn(incompleteBusinessRuleDefinition())

        val thrown = assertThrows<InvalidLlmReturnFormatException> {
            bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())
        }

        assertIs<IllegalArgumentException>(thrown.cause)
    }

    private fun mockValidContract() {
        whenCreateObject(
            { true },
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
        ).thenReturn(FlatContractTestFixtures.minimalContract())
    }

    private fun incompleteBusinessRuleDefinition() = FlatBpmnDefinition(
        processId = "Process_1",
        processName = "Dummy",
        nodes = listOf(
            FlatBpmnNode(id = "start", type = FlatBpmnNodeKind.START_EVENT, name = "Start"),
            // Missing decisionRef, required for BUSINESS_RULE_TASK: fails toSealed() mapping.
            FlatBpmnNode(id = "rule", type = FlatBpmnNodeKind.BUSINESS_RULE_TASK, name = "Decide"),
            FlatBpmnNode(id = "end", type = FlatBpmnNodeKind.END_EVENT, name = "End"),
        ),
        sequences = listOf(
            BpmnEdge(id = "flow1", sourceRef = "start", targetRef = "rule"),
            BpmnEdge(id = "flow2", sourceRef = "rule", targetRef = "end"),
        ),
    )

    private fun readyAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK")),
        evidence = listOf(SourceEvidence("ev1", "Unused", EvidenceSourceType.ORIGINAL_INPUT)),
        rationale = "Ready",
    )
}

private const val READY_PROSE = "When a user submits an order, we process it and then it is completed."
