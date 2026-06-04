/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnRequestResolver
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.BpmnReadinessState
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BpmnGenerationGateAgentTest {
    @Test
    fun `ready state becomes ready context`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir)
        val state = BpmnReadinessState(request(), assessment(ReadinessVerdict.READY))

        val ready = agent.approveReadyBpmnRequest(state)

        assertEquals(state.request, ready.request)
        assertEquals(state.assessment, ready.assessment)
        assertTrue(agent.bpmnRequestReady(state))
    }

    @Test
    fun `clarification answers become clarification exchanges and increment the round`(
        @TempDir tempDir: Path,
    ) {
        val invoker = SequencedReadinessInvoker(assessment(ReadinessVerdict.READY))
        val agent = agent(tempDir, invoker)
        val state = BpmnReadinessState(request(), assessment(ReadinessVerdict.NEEDS_CLARIFICATION), 0)

        val next = agent.applyClarificationAnswers(state, BpmnClarificationAnswers("Customer submits an order."))

        assertEquals(1, next.clarificationRound)
        val exchange = next.request.clarificationHistory.single()
        assertEquals("q-trigger", exchange.questionId)
        assertEquals("Customer submits an order.", exchange.answerText)
        assertEquals(ReadinessVerdict.READY, next.assessment.verdict)
    }

    @Test
    fun `clarification availability stops at exactly three rounds`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir)
        val roundTwo = BpmnReadinessState(request(), assessment(ReadinessVerdict.NEEDS_CLARIFICATION), 2)
        val roundThree = roundTwo.copy(clarificationRound = 3)

        assertTrue(agent.bpmnClarificationAvailable(roundTwo))
        assertFalse(agent.bpmnClarificationAvailable(roundThree))
        assertTrue(agent.bpmnClarificationLimitReached(roundThree))
    }

    @Test
    fun `blocked after clarification limit returns readiness result and report`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir, reportWriter = ReadinessReportWriter { _, _, _ -> "/tmp/readiness.md" })
        val state = BpmnReadinessState(request(outputFile = "out.bpmn"), assessment(ReadinessVerdict.NEEDS_CLARIFICATION), 3)

        val result = agent.readinessBlockedAfterClarificationLimit(state)

        assertEquals(BpmnGenerationStatus.NEEDS_CLARIFICATION, result.status)
        assertEquals("out.bpmn", result.outputFile)
        assertEquals("/tmp/readiness.md", result.reportFile)
    }

    private fun agent(
        tempDir: Path,
        invoker: BpmnReadinessInvoker = SequencedReadinessInvoker(assessment(ReadinessVerdict.NEEDS_CLARIFICATION)),
        reportWriter: ReadinessReportWriter = ReadinessReportWriter { _, _, _ -> "readiness.md" },
    ) = BpmnGenerationGateAgent(
        config = BpmnConfig(),
        requestResolver = BpmnRequestResolver(InputPathResolver(cwd = tempDir)),
        readinessInvoker = invoker,
        readinessReportWriter = reportWriter,
    )

    private fun request(outputFile: String? = null) = BpmnRequest(
        processDescription = "Ship an order",
        outputFile = outputFile,
    )

    private class SequencedReadinessInvoker(
        private vararg val assessments: ProcessInputAssessment,
    ) : BpmnReadinessInvoker {
        private var index = 0

        override fun assess(request: BpmnRequest): ProcessInputAssessment = assessments[index++]
    }

    private companion object {
        fun assessment(verdict: ReadinessVerdict) = ProcessInputAssessment(
            verdict = verdict,
            overallScore = if (verdict == ReadinessVerdict.READY) 100 else 40,
            dimensions = emptyList(),
            clarificationQuestions =
            if (verdict == ReadinessVerdict.NEEDS_CLARIFICATION) {
                listOf(ClarificationQuestion(id = "q-trigger", questionText = "What starts the process?"))
            } else {
                emptyList()
            },
            rationale = verdict.name,
        )
    }
}
