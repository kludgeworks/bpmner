/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength", "TooManyFunctions")

package dev.groknull.bpmner.rules.internal.adapter.inbound

import com.embabel.agent.test.unit.FakeOperationContext
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.LlmCheckRuleConfig
import dev.groknull.bpmner.rules.LlmRuleEvaluationRequest
import dev.groknull.bpmner.rules.LlmRuleSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LlmRuleAgentTest {
    @Test
    fun `empty rule list returns empty result without invoking the LLM`() {
        val context = FakeOperationContext()
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(definition = sampleDefinition(), rules = emptyList()),
            context,
        )

        assertTrue(result.diagnostics.isEmpty())
        assertEquals(0, context.llmInvocations.size)
    }

    @Test
    fun `single rule single batch produces one LLM invocation with rule guidance in the prompt`() {
        val context = FakeOperationContext()
        val rule = spec("business-clarity", "Prefer business language over technical detail.")
        context.expectResponse(
            LlmEvaluationResponse(
                violations = listOf(LlmRuleViolation(ruleId = "business-clarity", elementId = "t1", message = "Label is too technical")),
            ),
        )
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(definition = sampleDefinition(), rules = listOf(rule)),
            context,
        )

        assertEquals(1, context.llmInvocations.size)
        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("business-clarity"), "prompt should name the rule id")
        assertTrue(prompt.contains("Prefer business language over technical detail."), "prompt should embed the rule prompt text")
        assertEquals(1, result.diagnostics.size)
        assertEquals("business-clarity", result.diagnostics.single().ruleId)
        assertEquals("t1", result.diagnostics.single().elementId)
        assertEquals("Label is too technical", result.diagnostics.single().message)
    }

    @Test
    fun `multiple rules in one batch share a single LLM invocation`() {
        val context = FakeOperationContext()
        val rules = listOf(
            spec("rule-a", "First rule."),
            spec("rule-b", "Second rule."),
            spec("rule-c", "Third rule."),
        )
        context.expectResponse(LlmEvaluationResponse(violations = emptyList()))
        val agent = LlmRuleAgent(BpmnConfig())

        agent.evaluateLlmRules(LlmRuleEvaluationRequest(sampleDefinition(), rules), context)

        assertEquals(1, context.llmInvocations.size)
        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("rule-a"))
        assertTrue(prompt.contains("rule-b"))
        assertTrue(prompt.contains("rule-c"))
    }

    @Test
    fun `25 rules with batch size 8 produces 4 LLM invocations`() {
        val context = FakeOperationContext()
        val rules = (1..25).map { spec("rule-$it", "Rule $it prompt.") }
        // 25 rules / batch 8 = ceil(25/8) = 4 batches → expect 4 responses queued.
        repeat(4) { context.expectResponse(LlmEvaluationResponse(violations = emptyList())) }
        val agent = LlmRuleAgent(BpmnConfig(lintBatchSize = 8))

        agent.evaluateLlmRules(LlmRuleEvaluationRequest(sampleDefinition(), rules), context)

        assertEquals(4, context.llmInvocations.size, "ceil(25/8) = 4 batches expected")
    }

    @Test
    fun `violations for unknown rule ids are dropped with a warning rather than fabricating diagnostics`() {
        val context = FakeOperationContext()
        val rule = spec("known-rule", "Real rule.")
        context.expectResponse(
            LlmEvaluationResponse(
                violations = listOf(
                    LlmRuleViolation(ruleId = "known-rule", elementId = "t1", message = "real violation"),
                    LlmRuleViolation(ruleId = "fabricated-rule", elementId = "t2", message = "model hallucination"),
                ),
            ),
        )
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(LlmRuleEvaluationRequest(sampleDefinition(), listOf(rule)), context)

        assertEquals(1, result.diagnostics.size, "fabricated ruleId must be dropped")
        assertEquals("known-rule", result.diagnostics.single().ruleId)
    }

    @Test
    fun `empty violations response produces no diagnostics`() {
        val context = FakeOperationContext()
        val rule = spec("clean-rule", "Some rule.")
        context.expectResponse(LlmEvaluationResponse(violations = emptyList()))
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(LlmRuleEvaluationRequest(sampleDefinition(), listOf(rule)), context)

        assertEquals(1, context.llmInvocations.size)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `prompt embeds the BPMN definition as JSON`() {
        val context = FakeOperationContext()
        context.expectResponse(LlmEvaluationResponse(violations = emptyList()))
        val agent = LlmRuleAgent(BpmnConfig())

        agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(sampleDefinition(), listOf(spec("r1", "r1 prompt"))),
            context,
        )

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("\"processId\""), "definition JSON should include processId")
        assertTrue(prompt.contains("\"Process_T\""))
    }

    @Test
    fun `rubric appears in prompt when supplied`() {
        val context = FakeOperationContext()
        context.expectResponse(LlmEvaluationResponse(violations = emptyList()))
        val agent = LlmRuleAgent(BpmnConfig())

        agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(
                sampleDefinition(),
                listOf(spec("r1", "r1 prompt", rubric = "Flag only when the violation obscures business intent.")),
            ),
            context,
        )

        val prompt = context.llmInvocations.single().prompt
        assertTrue(prompt.contains("Rubric: Flag only when the violation obscures business intent."))
    }

    private fun spec(id: String, prompt: String, rubric: String? = null): LlmRuleSpec = LlmRuleSpec(
        metadata = RuleMetadata(
            id = id,
            name = id,
            slug = id,
            category = "Test",
            intent = "Test intent for $id.",
            forModellers = "Test guidance for modellers about $id.",
            forAI = "Test guidance for the model about $id.",
            targetElements = listOf("bpmn:UserTask"),
            errorMessages = mapOf("default" to "$id violation"),
            severity = RuleSeverity.WARNING,
        ),
        config = LlmCheckRuleConfig(prompt = prompt, rubric = rubric),
    )

    private fun sampleDefinition() = BpmnDefinition(
        processId = "Process_T",
        processName = "Test",
        nodes =
        listOf(
            BpmnStartEvent("s", "Start"),
            BpmnUserTask("t1", "Approve"),
            BpmnEndEvent("e", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("f1", "s", "t1"),
            BpmnEdge("f2", "t1", "e"),
        ),
    )
}
