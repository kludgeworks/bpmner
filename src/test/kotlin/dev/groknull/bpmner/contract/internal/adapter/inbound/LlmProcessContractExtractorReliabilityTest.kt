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
import dev.groknull.bpmner.authoring.BpmnGenerationStatus
import dev.groknull.bpmner.authoring.internal.adapter.outbound.AgentPlatformBpmnAgentInvoker
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.contract.BpmnContractExtractionException
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.SourceEvidence
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Regression guard for contract extraction's structured-output reliability: the framework's own
 * `InvalidLlmReturn*` failures — raised directly from the LLM call, before `flat.toSealed()` runs
 * — must end the run at a `CONTRACT_FAILED` terminal carrying a reason, rather than throwing out
 * of the process, and must stay [NonRetryable] so the stage is not repeated wholesale.
 *
 * Exercises the real `ProcessContractExtractor.extract(...)` call path via
 * [AgentPlatformBpmnAgentInvoker.generate], seeding a `READY` assessment directly (bypassing the
 * readiness LLM call) so the process reaches contract extraction in one pass, mirroring
 * `BpmnAlignmentFailureIntegrationTest`'s shape.
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
    fun `malformed contract output ends the run at a CONTRACT_FAILED terminal`() {
        val formatFailure = InvalidLlmReturnFormatException(
            "not json",
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
            RuntimeException("malformed"),
        )
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS).thenThrow(formatFailure)

        val result = bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())

        assertEquals(BpmnGenerationStatus.CONTRACT_FAILED, result.status)
        assertNotNull(result.failureDetail, "terminal must carry why the run stopped")
    }

    @Test
    fun `invalid contract output ends the run at a CONTRACT_FAILED terminal`() {
        val typeFailure = InvalidLlmReturnTypeException(returnedObject = "not-a-contract", constraintViolations = emptySet())
        whenCreateObject({ true }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS).thenThrow(typeFailure)

        val result = bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment())

        assertEquals(BpmnGenerationStatus.CONTRACT_FAILED, result.status)
        assertNotNull(result.failureDetail, "terminal must carry why the run stopped")
    }

    @Test
    fun `contract extraction failure stays non-retryable`() {
        // The framework retries any exception it is not told is permanent. Contract extraction is
        // several model calls, so an unmarked failure here would repeat the whole stage.
        val extractionFailure = BpmnContractExtractionException("boom")
        assertIs<NonRetryable>(extractionFailure)
        assertFalse(extractionFailure is Retryable)
    }

    private fun readyAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK")),
        evidence = listOf(SourceEvidence("ev1", "Unused")),
        rationale = "Ready",
    )
}

private const val READY_PROSE = "When a user submits an order, we process it and then it is completed."
