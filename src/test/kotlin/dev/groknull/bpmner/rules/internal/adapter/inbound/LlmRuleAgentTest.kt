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
        val diagnostic = result.diagnostics.single()
        assertEquals("business-clarity", diagnostic.ruleId)
        assertEquals("t1", diagnostic.elementId)
        assertEquals("Label is too technical", diagnostic.message)
        // diagnosticCode falls back to the rule id when errorMessages has only "default",
        // matching the codebase-wide convention (see api/RuleDiagnostic.kt) — diagnosticCode
        // is a stable per-check identifier for severity-override filtering, not a key into
        // errorMessages.
        assertEquals("business-clarity", diagnostic.diagnosticCode)
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

    @Test
    fun `multi non-default errorMessages keys produces rule-config-error per violation`() {
        // PR #249 G2: an LLM rule with two or more non-"default" errorMessages keys is
        // ambiguous — LlmRuleViolation carries no diagnosticCode so the model can't
        // signal which code applies. The agent surfaces the drift as rule-config-error
        // rather than silently picking one code.
        val context = FakeOperationContext()
        val ambiguousMetadata = RuleMetadata(
            id = "ambiguous",
            name = "ambiguous",
            slug = "ambiguous",
            category = "Test",
            intent = "Test",
            forModellers = "Test",
            forAI = "Test",
            targetElements = listOf("bpmn:UserTask"),
            errorMessages = mapOf(
                "first-issue" to "first kind of issue",
                "second-issue" to "second kind of issue",
                "default" to "fallback",
            ),
            severity = RuleSeverity.WARNING,
        )
        val ambiguous = LlmRuleSpec(
            metadata = ambiguousMetadata,
            config = LlmCheckRuleConfig(prompt = "evaluate"),
        )
        context.expectResponse(
            LlmEvaluationResponse(
                violations = listOf(LlmRuleViolation(ruleId = "ambiguous", elementId = "t1", message = "anything")),
            ),
        )
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(sampleDefinition(), listOf(ambiguous)),
            context,
        )

        val diagnostic = result.diagnostics.single()
        assertEquals("rule-config-error", diagnostic.diagnosticCode)
        assertEquals(RuleSeverity.ERROR, diagnostic.severity)
        assertTrue(diagnostic.message.contains("ambiguous"), "config-error should name the offending rule")
        assertTrue(
            diagnostic.message.contains("first-issue") && diagnostic.message.contains("second-issue"),
            "config-error should list the conflicting keys",
        )
    }

    @Test
    fun `single non-default errorMessages key flows through as diagnosticCode`() {
        // Companion to the multi-key test: when the rule has exactly one non-"default"
        // key, that key becomes the diagnosticCode (not the rule id). This is the
        // CompositeCheck-shaped single-code rule scenario.
        val context = FakeOperationContext()
        val singleCoded = LlmRuleSpec(
            metadata = RuleMetadata(
                id = "single-coded",
                name = "single-coded",
                slug = "single-coded",
                category = "Test",
                intent = "Test",
                forModellers = "Test",
                forAI = "Test",
                targetElements = listOf("bpmn:UserTask"),
                errorMessages = mapOf(
                    "the-only-code" to "violation message",
                    "default" to "fallback",
                ),
                severity = RuleSeverity.WARNING,
            ),
            config = LlmCheckRuleConfig(prompt = "evaluate"),
        )
        context.expectResponse(
            LlmEvaluationResponse(
                violations = listOf(LlmRuleViolation(ruleId = "single-coded", elementId = "t1", message = "msg")),
            ),
        )
        val agent = LlmRuleAgent(BpmnConfig())

        val result = agent.evaluateLlmRules(
            LlmRuleEvaluationRequest(sampleDefinition(), listOf(singleCoded)),
            context,
        )

        assertEquals("the-only-code", result.diagnostics.single().diagnosticCode)
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
