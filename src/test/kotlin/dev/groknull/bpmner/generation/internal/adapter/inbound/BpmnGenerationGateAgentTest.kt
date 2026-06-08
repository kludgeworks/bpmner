/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.BpmnRequestResolver
import dev.groknull.bpmner.core.ClarificationExchange
import dev.groknull.bpmner.core.InputPathResolver
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.readiness.BpmnClarificationAnswers
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
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
    fun `ready request becomes ready context`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir)
        val request = request()
        val ready = assessment(ReadinessVerdict.READY)

        val context = agent.approveReadyRequest(request, ready)

        assertEquals(request, context.request)
        assertEquals(ready, context.assessment)
        assertTrue(agent.assessmentReady(ready))
    }

    @Test
    fun `clarification answers are recorded and trigger reassessment`(
        @TempDir tempDir: Path,
    ) {
        val invoker = SequencedReadinessInvoker(assessment(ReadinessVerdict.READY))
        val agent = agent(tempDir, invoker)

        val next =
            agent.applyClarificationAnswers(
                interactiveRequest(),
                assessment(ReadinessVerdict.NEEDS_CLARIFICATION),
                BpmnClarificationAnswers("Customer submits an order."),
            )

        assertEquals(ReadinessVerdict.READY, next.verdict)
        val reassessed = invoker.lastRequest!!
        val exchange = reassessed.clarificationHistory.single()
        assertEquals("q-trigger", exchange.questionId)
        assertEquals("Customer submits an order.", exchange.answerText)
    }

    @Test
    fun `interactive clarification stops at exactly three rounds`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir)
        val needs = assessment(ReadinessVerdict.NEEDS_CLARIFICATION)

        assertTrue(agent.clarificationAvailable(needs, interactiveRequest(clarificationRounds = 2)))
        assertFalse(agent.clarificationAvailable(needs, interactiveRequest(clarificationRounds = 3)))
        assertTrue(agent.clarificationBlocked(needs, interactiveRequest(clarificationRounds = 3)))
    }

    @Test
    fun `single-shot needs-clarification is blocked without asking`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir)
        val needs = assessment(ReadinessVerdict.NEEDS_CLARIFICATION)
        val singleShot = request() // defaults to SINGLE_SHOT

        assertFalse(agent.clarificationAvailable(needs, singleShot))
        assertTrue(agent.clarificationBlocked(needs, singleShot))
    }

    @Test
    fun `blocked readiness returns needs-clarification result and report`(
        @TempDir tempDir: Path,
    ) {
        val agent = agent(tempDir, reportWriter = ReadinessReportWriter { _, _, _ -> "/tmp/readiness.md" })

        val result =
            agent.readinessBlocked(
                request(outputFile = "out.bpmn"),
                assessment(ReadinessVerdict.NEEDS_CLARIFICATION),
            )

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

    private fun interactiveRequest(clarificationRounds: Int = 0) = request().copy(
        mode = GenerationMode.INTERACTIVE,
        clarificationHistory =
        List(clarificationRounds) { index ->
            ClarificationExchange(
                questionId = "q$index",
                questionText = "Question $index?",
                answerText = "Answer $index.",
            )
        },
    )

    private class SequencedReadinessInvoker(
        private vararg val assessments: ProcessInputAssessment,
    ) : BpmnReadinessInvoker {
        private var index = 0
        var lastRequest: BpmnRequest? = null
            private set

        override fun assess(request: BpmnRequest): ProcessInputAssessment {
            lastRequest = request
            return assessments[index++]
        }
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
