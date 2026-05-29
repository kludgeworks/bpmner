/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.assertSilent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.context
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.evaluate
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertNull

/**
 * Per-rule test for `gen-no-duplicate-diagrams`. The rule fires when a BPMN document carries
 * more than one `bpmndi:BPMNDiagram` element. The source of truth is the document-level
 * `BpmnDefinition.diagramCount` field surfaced by `BpmnXmlToDefinitionConverter`; the rule's
 * `CardinalityCheck` reads it via the synthetic `bpmndi:BPMNDiagram` `PrimitiveElement`
 * entries injected by `PrimitiveModelMapping`. Operators can override the policy via
 * `bpmner.rules.severity-overrides`.
 */
internal class NoDuplicateDiagramsTest {
    private val rule = loadRule("gen-no-duplicate-diagrams")

    private val nodes: List<BpmnNode> = listOf(
        BpmnStartEvent("s", "Start"),
        BpmnEndEvent("e", "End"),
    )

    @Test
    fun `silent when document has zero diagrams`() {
        // Semantic-only XML carries no DI elements, so `diagramCount = 0` is the normal case.
        assertSilent(rule, context(nodes = nodes, diagramCount = 0))
    }

    @Test
    fun `silent when document has exactly one diagram`() {
        assertSilent(rule, context(nodes = nodes, diagramCount = 1))
    }

    @Test
    fun `fires when document has two diagrams`() {
        // CardinalityCheck emits one diagnostic with elementId = null because diagrams are
        // document-level metadata, not graph nodes. PklRuleTestSupport.assertFires takes a
        // non-null Collection<String> so we drop down to evaluate() + manual assertions here.
        val diagnostics = evaluate(rule, context(nodes = nodes, diagramCount = 2))
        assertEquals(1, diagnostics.size, "expected exactly one diagnostic, got $diagnostics")
        assertNull(diagnostics.single().elementId, "diagnostic should anchor at the document root (null elementId)")
        assertEquals("gen-no-duplicate-diagrams", diagnostics.single().ruleId)
    }
}
