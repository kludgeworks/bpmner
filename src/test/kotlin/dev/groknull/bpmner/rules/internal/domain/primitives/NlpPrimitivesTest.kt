/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.rules.internal.domain.nlp.testBpmnNlp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the three NLP-aware primitives shipped in Phase 3 (#218). Each primitive
 * carries ≥3 `@Test` methods per the bar set in Phase 2H (#245) — covers a fires-case, a
 * passes-case, and a boundary.
 */
class NlpPrimitivesTest {
    private val nlp = testBpmnNlp()

    // ---------------------------------------------------------------------------------
    // PartOfSpeechCheck

    @Test
    fun `PartOfSpeechCheck LEADING_MUST_BE VERB fires when activity name does not start with a verb`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("ok", "Process the order"),
                BpmnUserTask("bad", "Order processing"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = PartOfSpeechCheck(nlp).evaluate(
            ctx,
            metadata("must-be-verb", "bpmn:UserTask"),
            PartOfSpeechCheckConfig("name", PartOfSpeechMode.LEADING_MUST_BE, NlpPosTag.VERB),
        )
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `PartOfSpeechCheck LEADING_MUST_NOT_BE VERB fires when gateway label is action-shaped`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnExclusiveGateway("ok", "Order valid"),
                BpmnExclusiveGateway("bad", "Validate the order"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = PartOfSpeechCheck(nlp).evaluate(
            ctx,
            metadata("no-leading-verb", "bpmn:ExclusiveGateway"),
            PartOfSpeechCheckConfig("name", PartOfSpeechMode.LEADING_MUST_NOT_BE, NlpPosTag.VERB),
        )
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `PartOfSpeechCheck does not fire on blank property values`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("blank"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = PartOfSpeechCheck(nlp).evaluate(
            ctx,
            metadata("must-be-verb", "bpmn:UserTask"),
            PartOfSpeechCheckConfig("name", PartOfSpeechMode.LEADING_MUST_BE, NlpPosTag.VERB),
        )
        assertTrue(diagnostics.isEmpty(), "blank names should be deferred to RequiredPropertyCheck, not flagged here")
    }

    // ---------------------------------------------------------------------------------
    // LemmaCheck

    @Test
    fun `LemmaCheck REQUIRE_LEADING_LEMMA fires when the leading lemma is not in the vocab`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnExclusiveGateway("ok", "Is the order valid?"),
                BpmnExclusiveGateway("bad", "Order valid"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = LemmaCheck(nlp).evaluate(
            ctx,
            metadata("interrogative", "bpmn:ExclusiveGateway"),
            LemmaCheckConfig("name", LemmaMode.REQUIRE_LEADING_LEMMA, listOf("be", "do", "have", "can", "will")),
        )
        assertEquals(listOf("bad"), diagnostics.map { it.elementId })
    }

    @Test
    fun `LemmaCheck case-folds configured lemmas`() {
        // Lemmas are written in any case by the rule author — the primitive must fold them
        // before comparison so the config is robust to typing accidents.
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnExclusiveGateway("g", "Are we done?"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = LemmaCheck(nlp).evaluate(
            ctx,
            metadata("interrogative", "bpmn:ExclusiveGateway"),
            LemmaCheckConfig("name", LemmaMode.REQUIRE_LEADING_LEMMA, listOf("BE", "Do", "have")),
        )
        assertTrue(diagnostics.isEmpty(), "Lemma 'be' should match leading 'Are' regardless of case")
    }

    @Test
    fun `LemmaCheck FORBID_ANY_LEMMA fires when any token's lemma is in the vocab`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("clean", "Submit the application"),
                BpmnUserTask("dirty", "Process the order quickly"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = LemmaCheck(nlp).evaluate(
            ctx,
            metadata("no-process", "bpmn:UserTask"),
            LemmaCheckConfig("name", LemmaMode.FORBID_ANY_LEMMA, listOf("process")),
        )
        assertEquals(listOf("dirty"), diagnostics.map { it.elementId })
    }

    // ---------------------------------------------------------------------------------
    // GrammaticalShapeCheck

    @Test
    fun `GrammaticalShapeCheck STATE_LABEL passes for past-participle and noun-led labels`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("pp", "Order received"),
                BpmnUserTask("noun", "Customer details"),
                BpmnUserTask("action", "Send the notification"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = GrammaticalShapeCheck(nlp).evaluate(
            ctx,
            metadata("state", "bpmn:UserTask"),
            GrammaticalShapeCheckConfig("name", GrammaticalShape.STATE_LABEL),
        )
        assertEquals(listOf("action"), diagnostics.map { it.elementId })
    }

    @Test
    fun `GrammaticalShapeCheck QUESTION_FORM passes for WH-led and auxiliary-led labels`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnExclusiveGateway("wh", "Why was it rejected"),
                BpmnExclusiveGateway("aux", "Is the order valid"),
                BpmnExclusiveGateway("punct", "It worked?"),
                BpmnExclusiveGateway("nope", "Order processing"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = GrammaticalShapeCheck(nlp).evaluate(
            ctx,
            metadata("question", "bpmn:ExclusiveGateway"),
            GrammaticalShapeCheckConfig("name", GrammaticalShape.QUESTION_FORM),
        )
        assertEquals(listOf("nope"), diagnostics.map { it.elementId })
    }

    @Test
    fun `GrammaticalShapeCheck ACTION_LABEL fires when leading token is not a bare-infinitive action verb`() {
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("ok", "Process the order"),
                BpmnUserTask("pp-not-action", "Order received"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val diagnostics = GrammaticalShapeCheck(nlp).evaluate(
            ctx,
            metadata("action", "bpmn:UserTask"),
            GrammaticalShapeCheckConfig("name", GrammaticalShape.ACTION_LABEL),
        )
        // The past-participle "received" leads a STATE label, not an ACTION label, so it
        // must fire here even though it's morphologically verbal.
        assertEquals(listOf("pp-not-action"), diagnostics.map { it.elementId })
    }

    private fun metadata(id: String, vararg targetElements: String): RuleMetadata = RuleMetadata(
        id = id,
        name = id,
        slug = id,
        category = "Test",
        intent = "Test rule.",
        forModellers = "Test rule.",
        forAI = "Test rule.",
        targetElements = targetElements.toList(),
        errorMessages = mapOf("default" to "$id violation"),
        severity = RuleSeverity.WARNING,
    )

    private fun context(
        nodes: List<dev.groknull.bpmner.core.BpmnNode>,
        edges: List<BpmnEdge>? = null,
    ): BpmnDefinitionContext {
        val actualEdges = edges ?: nodes.zipWithNext().mapIndexed { index, (source, target) ->
            BpmnEdge("f${index + 1}", source.id, target.id)
        }
        return BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = nodes,
                sequences = actualEdges.ifEmpty { listOf(BpmnEdge("f", nodes.first().id, nodes.last().id)) },
            ),
        )
    }
}
