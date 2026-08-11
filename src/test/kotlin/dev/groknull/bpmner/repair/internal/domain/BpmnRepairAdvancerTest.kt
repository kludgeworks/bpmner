/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.authoring.BpmnConformance
import dev.groknull.bpmner.authoring.BpmnContractConformancePort
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnRepairConfig
import dev.groknull.bpmner.repair.internal.adapter.outbound.RepairFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BpmnRepairAdvancerTest {
    private val conformancePort = mock(BpmnContractConformancePort::class.java)
    private val contractAwareValidator = mock(BpmnContractAwareValidator::class.java)
    private val attemptRecordFactory = mock(BpmnAttemptRecordFactory::class.java)
    private val promptFactory = mock(BpmnRepairPromptPort::class.java)
    private val fingerprints = mock(BpmnFingerprintService::class.java)
    private val processGenerator = mock(BpmnProcessGenerator::class.java)

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun advancer(trimHistory: Boolean): BpmnRepairAdvancer {
        val config = BpmnRepairConfig(trimHistory = trimHistory)
        return BpmnRepairAdvancer(
            conformancePort,
            contractAwareValidator,
            attemptRecordFactory,
            promptFactory,
            fingerprints,
            processGenerator,
            config,
        )
    }

    @Test
    fun `revalidateAndAdvance appends messages when trimHistory is false`() {
        val adv = advancer(trimHistory = false)
        val priorMessages = listOf<Message>(UserMessage("Prompt"), AssistantMessage("Def1"))
        val priorEval = evaluation(priorMessages)
        val definition = mock(BpmnDefinition::class.java)
        val graph = mock(LaidOutProcessGraph::class.java)
        val rendered = mock(RenderedBpmn::class.java)

        `when`(conformancePort.conform(anyNonNull(), anyNonNull())).thenReturn(BpmnConformance(definition, emptyList()))
        `when`(fingerprints.definitionFingerprint(anyNonNull())).thenReturn("fp-stamped", "fp-prior")
        `when`(fingerprints.promptFingerprint(anyNonNull())).thenReturn("fp-prompt")
        `when`(processGenerator.render(anyNonNull())).thenReturn(rendered)
        `when`(
            contractAwareValidator.evaluate(
                anyNonNull(),
                anyNonNull(),
                ArgumentMatchers.any(),
                anyNonNull(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(),
            ),
        ).thenReturn(mock(BpmnEvaluation::class.java))
        `when`(attemptRecordFactory.toRecord(anyNonNull(), ArgumentMatchers.any()))
            .thenReturn(mock(BpmnAttemptRecord::class.java))

        val appended = listOf<Message>(UserMessage("Feedback"), AssistantMessage("Def2"))
        val next = adv.revalidateAndAdvance(priorEval, definition, appended, "prompt", modelRepair = false)

        assertEquals(4, next.messages.size)
        assertEquals(priorMessages[0], next.messages[0])
        assertEquals(priorMessages[1], next.messages[1])
        assertEquals(appended[0], next.messages[2])
        assertEquals(appended[1], next.messages[3])
        assertEquals(0, next.repairAttempts)
        assertEquals(1, next.history.size)
        verify(attemptRecordFactory, never()).toRecord(anyNonNull(), ArgumentMatchers.any())
    }

    @Test
    fun `revalidateAndAdvance trims messages when trimHistory is true`() {
        val adv = advancer(trimHistory = true)
        val priorMessages = listOf<Message>(UserMessage("Prompt"), AssistantMessage("Def1"))
        val priorEval = evaluation(priorMessages)
        val definition = mock(BpmnDefinition::class.java)
        val graph = mock(LaidOutProcessGraph::class.java)
        val rendered = mock(RenderedBpmn::class.java)

        `when`(conformancePort.conform(anyNonNull(), anyNonNull())).thenReturn(BpmnConformance(definition, emptyList()))
        `when`(fingerprints.definitionFingerprint(anyNonNull())).thenReturn("fp-stamped", "fp-prior")
        `when`(fingerprints.promptFingerprint(anyNonNull())).thenReturn("fp-prompt")
        `when`(fingerprints.serializeDefinition(anyNonNull())).thenReturn("compact-serialized")
        `when`(processGenerator.render(anyNonNull())).thenReturn(rendered)
        `when`(
            contractAwareValidator.evaluate(
                anyNonNull(),
                anyNonNull(),
                ArgumentMatchers.any(),
                anyNonNull(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyInt(),
            ),
        ).thenReturn(mock(BpmnEvaluation::class.java))
        `when`(attemptRecordFactory.toRecord(anyNonNull(), ArgumentMatchers.any()))
            .thenReturn(mock(BpmnAttemptRecord::class.java))

        val appended = listOf<Message>(UserMessage("Feedback"), AssistantMessage("Def2"))
        val next = adv.revalidateAndAdvance(priorEval, definition, appended, "prompt", modelRepair = true)

        assertEquals(2, next.messages.size)
        assertEquals(priorMessages[0], next.messages[0])
        assertEquals("compact-serialized", (next.messages[1] as AssistantMessage).content)
    }

    private fun evaluation(messages: List<Message>): BpmnRepairEvaluation {
        val definition = RepairFixtures.sampleDefinition()
        val attempt = RepairFixtures.attempt(definition, emptyList())
        val lastRecord = mock(BpmnAttemptRecord::class.java)
        `when`(lastRecord.definitionFingerprint).thenReturn("fp-prior")
        val history = BpmnAttemptHistory(listOf(lastRecord))

        return BpmnRepairEvaluation(
            request = BpmnRequest("desc"),
            graph = attempt.graph,
            rendered = attempt.evaluation.rendered,
            evaluation = attempt.evaluation,
            messages = messages,
            history = history,
            contract = mock(ProcessContract::class.java),
            repairAttempts = 0,
        )
    }
}
