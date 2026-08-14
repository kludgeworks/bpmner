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

    @Test
    fun `a corrective attempt receives the previous contract in its prompt`() {
        // Attempt 1 has no previousIssues yet, so its prompt cannot carry the marker. It returns
        // a contract that fails validation (act-orphan has no flow in or out — V6), which drives
        // a corrective attempt. The marker lives in the activity's NAME, not its id: V6's message
        // quotes only the id ("element 'act-orphan' is declared but..."), so previousIssues alone
        // can never leak the marker — only rendering the full previousContract can.
        whenCreateObject({ prompt -> MARKER_TOKEN !in prompt }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)
            .thenReturn(invalidContractWithMarkerActivity())
        // Only matches once the corrective prompt renders the previous contract. Without commit
        // 7's fix, this predicate never matches and the run exhausts its attempts on the same
        // invalid contract instead.
        whenCreateObject({ prompt -> MARKER_TOKEN in prompt }, FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS)
            .thenReturn(FlatContractTestFixtures.minimalContract())

        // Contract extraction succeeding drives the run into the next (unstubbed) stage, which
        // is out of scope here — only the contract-extraction prompt is under test.
        runCatching { bpmnAgentInvoker.generate(BpmnRequest(processDescription = READY_PROSE), readyAssessment()) }

        verifyCreateObjectMatching(
            { prompt -> MARKER_TOKEN in prompt },
            FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS,
        ) { true }
    }

    private fun invalidContractWithMarkerActivity(): Any = FlatProcessContract(
        id = "contract-orphan",
        processName = "Orphan process",
        summary = "Summary",
        start = FlatContractStart(
            trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Trigger"),
            sourceIds = listOf("ev1"),
        ),
        activities = listOf(
            FlatContractActivity(id = "a1", name = "A1", kind = FlatActivityKind.SERVICE, sourceIds = listOf("ev1")),
            FlatContractActivity(
                id = "act-orphan",
                name = MARKER_TOKEN,
                kind = FlatActivityKind.SERVICE,
                sourceIds = listOf("ev1"),
            ),
        ),
        endStates = listOf(
            FlatContractEndState(id = "e1", name = "E1", kind = FlatEndStateKind.NORMAL, sourceIds = listOf("ev1")),
        ),
        // a1 is wired end to end; act-orphan has no incoming or outgoing flow (V6).
        flows = listOf(
            FlatContractFlow(from = "start", to = "a1"),
            FlatContractFlow(from = "a1", to = "e1"),
        ),
    )

    private fun readyAssessment() = ProcessInputAssessment(
        verdict = ReadinessVerdict.READY,
        overallScore = 90,
        dimensions = listOf(ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 90, "OK")),
        evidence = listOf(SourceEvidence("ev1", "Unused")),
        rationale = "Ready",
    )
}

private const val READY_PROSE = "When a user submits an order, we process it and then it is completed."
private const val MARKER_TOKEN = "Marker only in previous contract render zqxwv"
