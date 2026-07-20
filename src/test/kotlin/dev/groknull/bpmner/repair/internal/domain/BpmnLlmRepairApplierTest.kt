/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnElementIndex
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.llm.StructuredOutputFailureCategory
import dev.groknull.bpmner.llm.StructuredOutputFailureEvent
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnRepairConfig
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnDefinition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertIs

/**
 * Regression guard for repair's observability-only fix (epic #592 stage 2): each of the three
 * used LLM call sites (`applyLlmLabelPatch`, `applyLlmStructuralPatch`, `applyFullLlmRewrite`)
 * must publish a [StructuredOutputFailureEvent] with the matching role string
 * (`repair-label`/`repair-patch`/`repair-rewrite`) when the underlying prompt runner throws one
 * of Embabel's `InvalidLlmReturn*` exceptions, while the existing `ReplanRequestedException`
 * translation stays exactly as-is (behavior unchanged, observability added — wrap, don't
 * replace).
 */
class BpmnLlmRepairApplierTest {
    private val promptFactory = mock(BpmnRepairPromptPort::class.java)
    private val patchApplier = mock(BpmnPatchApplicationPort::class.java)
    private val advancer = mock(BpmnRepairAdvancer::class.java)
    private val config = BpmnRepairConfig()
    private val eventPublisher = mock(ApplicationEventPublisher::class.java)
    private val context = mock(ActionContext::class.java)
    private val ai = mock(Ai::class.java)
    private val runner = mock(PromptRunner::class.java, Mockito.RETURNS_SELF)

    private val applier = BpmnLlmRepairApplier(promptFactory, patchApplier, advancer, config, eventPublisher)

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @BeforeEach
    fun setUp() {
        `when`(context.ai()).thenReturn(ai)
        `when`(ai.withLlm(anyNonNull<LlmOptions>())).thenReturn(runner)
        `when`(runner.withPromptContributor(anyNonNull())).thenReturn(runner)
        `when`(promptFactory.patchFeedback(anyNonNull(), anyNonNull())).thenReturn("feedback")
        `when`(promptFactory.fullRepairFeedback(anyNonNull(), anyNonNull())).thenReturn("feedback")
        `when`(promptFactory.lintRuleDocsPrompt(anyNonNull())).thenReturn(null)
        `when`(advancer.revalidateAndAdvance(anyNonNull(), anyNonNull(), anyNonNull(), anyNonNull()))
            .thenReturn(mock(BpmnRepairEvaluation::class.java))
    }

    @Test
    fun `applyLlmLabelPatch publishes a repair-label event and still replans on InvalidLlmReturnFormatException`() {
        val failure = InvalidLlmReturnFormatException("not json", BpmnRepairPatch::class.java, RuntimeException("malformed"))

        @Suppress("UNCHECKED_CAST")
        val creating = mock(PromptRunner.Creating::class.java) as PromptRunner.Creating<BpmnRepairPatch>
        `when`(runner.creating(BpmnRepairPatch::class.java)).thenReturn(creating)
        `when`(creating.withoutProperties("node", "edge")).thenReturn(creating)
        `when`(creating.fromMessages(anyNonNull<List<Message>>())).thenThrow(failure)

        assertThrows<ReplanRequestedException> {
            applier.applyLlmLabelPatch(evaluation(), context, emptyList())
        }

        verify(eventPublisher).publishEvent(
            ArgumentMatchers.argThat<StructuredOutputFailureEvent> {
                it.role == "repair-label" && it.category == StructuredOutputFailureCategory.INVALID_FORMAT
            },
        )
    }

    @Test
    fun `applyLlmStructuralPatch publishes a repair-patch event and still replans on InvalidLlmReturnFormatException`() {
        val failure = InvalidLlmReturnFormatException("not json", BpmnRepairPatch::class.java, RuntimeException("malformed"))
        Mockito.doThrow(failure).`when`(runner)
            .createObject(anyNonNull<List<Message>>(), anyNonNull<Class<BpmnRepairPatch>>())

        assertThrows<ReplanRequestedException> {
            applier.applyLlmStructuralPatch(evaluation(), context, emptyList())
        }

        verify(eventPublisher).publishEvent(
            ArgumentMatchers.argThat<StructuredOutputFailureEvent> {
                it.role == "repair-patch" && it.category == StructuredOutputFailureCategory.INVALID_FORMAT
            },
        )
    }

    @Test
    fun `applyFullLlmRewrite publishes a repair-rewrite event and still replans on InvalidLlmReturnFormatException`() {
        val failure = InvalidLlmReturnFormatException(
            "not json",
            FlatBpmnDefinition::class.java,
            RuntimeException("malformed"),
        )
        Mockito.doThrow(failure).`when`(runner)
            .createObject(anyNonNull<List<Message>>(), anyNonNull<Class<FlatBpmnDefinition>>())

        val thrown = assertThrows<ReplanRequestedException> {
            applier.applyFullLlmRewrite(evaluation(), context, emptyList())
        }

        assertIs<InvalidLlmReturnFormatException>(thrown.cause)
        verify(eventPublisher).publishEvent(
            ArgumentMatchers.argThat<StructuredOutputFailureEvent> {
                it.role == "repair-rewrite" && it.category == StructuredOutputFailureCategory.INVALID_FORMAT
            },
        )
    }

    private fun evaluation(): BpmnRepairEvaluation {
        val definition = mock(BpmnDefinition::class.java)
        val contract = mock(ProcessContract::class.java)
        val graph = mock(LaidOutProcessGraph::class.java)
        val elementIndex = BpmnElementIndex(processId = "process-1", nodeObjectRefs = emptyMap(), edgeObjectRefs = emptyMap())
        val rendered = RenderedBpmn(definition = definition, xml = "<process/>", elementIndex = elementIndex)
        val bpmnEvaluation = BpmnEvaluation(
            definition = definition,
            rendered = rendered,
            diagnostics = emptyList(),
            globalDiagnostics = GlobalDiagnostics(emptyList()),
            validatedXml = "<process/>",
        )
        return BpmnRepairEvaluation(
            request = BpmnRequest(processDescription = "desc"),
            graph = graph,
            rendered = rendered,
            evaluation = bpmnEvaluation,
            messages = emptyList(),
            history = BpmnAttemptHistory(),
            contract = contract,
            repairAttempts = 0,
        )
    }
}
