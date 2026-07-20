/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.Retryable
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest
import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.contract.BpmnContractExtractionException
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression guard for the contract-extraction structured-output reliability fix (epic #592
 * stage 2): the framework's own `InvalidLlmReturn*` failures — raised directly from the LLM call,
 * before `flat.toSealed()` runs — must translate to a [BpmnContractExtractionException] marked
 * [NonRetryable], not ride the action's default retry policy alongside the legitimate
 * `RetryableBpmnGenerationException` structural-incompleteness path. Exercises the real
 * `ProcessContractExtractor.extract(...)` call path via [AgentPlatformBpmnAgentInvoker.generate],
 * seeding a `READY` assessment directly (bypassing the readiness LLM call) so the process reaches
 * contract extraction in one pass, mirroring `BpmnAlignmentFailureIntegrationTest`'s shape.
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
class LlmProcessContractExtractorReliabilityTest : EmbabelMockitoIntegrationTest() {
    @Autowired
    private lateinit var bpmnAgentInvoker: AgentPlatformBpmnAgentInvoker

    @Test
    fun `InvalidLlmReturnFormatException from the contract model surfaces as a NonRetryable BpmnContractExtractionException`() {
        val formatFailure = InvalidLlmReturnFormatException(
            "not json",
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
            RuntimeException("malformed"),
        )
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS).thenThrow(formatFailure)

        val thrown = assertThrows<BpmnContractExtractionException> {
            bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())
        }

        assertIs<InvalidLlmReturnFormatException>(thrown.cause)
        assertTrue(thrown is NonRetryable)
        assertFalse(thrown is Retryable)
    }

    @Test
    fun `InvalidLlmReturnTypeException from the contract model surfaces as a NonRetryable BpmnContractExtractionException`() {
        val typeFailure = InvalidLlmReturnTypeException(returnedObject = "not-a-contract", constraintViolations = emptySet())
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS).thenThrow(typeFailure)

        val thrown = assertThrows<BpmnContractExtractionException> {
            bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())
        }

        assertIs<InvalidLlmReturnTypeException>(thrown.cause)
        assertTrue(thrown is NonRetryable)
    }

    private fun readyAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK")),
        evidence = listOf(SourceEvidence("ev1", "Unused", EvidenceSourceType.ORIGINAL_INPUT)),
        rationale = "Ready",
    )
}

private const val READY_PROSE = "When a user submits an order, we process it and then it is completed."
