/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.readiness.BpmnReadinessThresholdsConfig
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.EvidenceSourceType
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.SourceEvidence
import org.mockito.Mockito.mock
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals

class BpmnReadinessAgentTest {
    @Test
    fun `assessReadiness returns normalized assessment without downgrading high score`() {
        val context = FakeOperationContext()
        context.expectResponse(assessment(ReadinessVerdict.READY, 92))
        val eventPublisher = mock(ApplicationEventPublisher::class.java)
        val agent = BpmnReadinessAgent(BpmnReadinessConfig(), BpmnReadinessThresholdsConfig(), eventPublisher)

        val result =
            agent.assessReadiness(
                BpmnRequest("Dashboard color choices only"),
                context,
            )

        // The high score of 92 is preserved (no hardcoded word list veto/downgrade occurs)
        assertEquals(ReadinessVerdict.READY, result.verdict)
        assertEquals(92, result.overallScore)
        assertEquals(1, context.llmInvocations.size)
    }

    @Test
    fun `normalize backfills missing dimension entries and clamps scores`() {
        val assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.READY,
            overallScore = 120, // Should be clamped to 100
            dimensions = listOf(
                ReadinessDimensionScore(ReadinessDimension.START_TRIGGER, 150, "Extremely ready"), // Clamped to 100
            ),
            rationale = "Model rationale.",
        )

        val normalized = assessment.normalize(readyThreshold = 75, maxClarificationQuestions = 5)

        assertEquals(100, normalized.overallScore)
        assertEquals(ReadinessVerdict.READY, normalized.verdict)

        // All dimensions must be present
        assertEquals(ReadinessDimension.entries.size, normalized.dimensions.size)
        val startTriggerScore = normalized.dimensions.single { it.dimension == ReadinessDimension.START_TRIGGER }
        assertEquals(100, startTriggerScore.score)

        val otherDimensionScore = normalized.dimensions.single { it.dimension == ReadinessDimension.END_STATES }
        assertEquals(50, otherDimensionScore.score)
    }

    @Test
    fun `normalize backfills blank ids in evidence and questions`() {
        val assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 60,
            dimensions = ReadinessDimension.entries.map {
                ReadinessDimensionScore(it, 60, "Rationale")
            },
            evidence = listOf(
                SourceEvidence(text = "Some evidence", sourceType = EvidenceSourceType.ORIGINAL_INPUT),
                SourceEvidence(id = "existing-ev", text = "Other evidence", sourceType = EvidenceSourceType.ORIGINAL_INPUT),
            ),
            clarificationQuestions = listOf(
                ClarificationQuestion("", "What starts it?"),
                ClarificationQuestion("existing-q", "Who is responsible?"),
            ),
            rationale = "Model rationale.",
        )

        val normalized = assessment.normalize(readyThreshold = 75, maxClarificationQuestions = 5)

        assertEquals(listOf("ev-1", "existing-ev"), normalized.evidence.map { it.id })
        assertEquals(listOf("q1", "existing-q"), normalized.clarificationQuestions.map { it.id })
    }

    @Test
    fun `normalize limits clarification questions count`() {
        val assessment = ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 60,
            dimensions = ReadinessDimension.entries.map {
                ReadinessDimensionScore(it, 60, "Rationale")
            },
            clarificationQuestions = listOf(
                ClarificationQuestion("", "What starts it?"),
                ClarificationQuestion("", "Who is responsible?"),
                ClarificationQuestion("", "What ends it?"),
            ),
            rationale = "Model rationale.",
        )

        val normalized = assessment.normalize(readyThreshold = 75, maxClarificationQuestions = 2)

        assertEquals(2, normalized.clarificationQuestions.size)
        assertEquals(listOf("q1", "q2"), normalized.clarificationQuestions.map { it.id })
    }

    private fun assessment(
        verdict: ReadinessVerdict,
        score: Int,
    ) = ProcessInputAssessment(
        verdict = verdict,
        overallScore = score,
        dimensions =
        ReadinessDimension.entries.map {
            ReadinessDimensionScore(
                dimension = it,
                score = score,
                rationale = "Model score for ${it.name}.",
            )
        },
        rationale = "Model rationale.",
    )
}
