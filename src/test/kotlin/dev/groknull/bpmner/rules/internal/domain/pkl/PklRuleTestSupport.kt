/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.rules.internal.domain.PklRuleCatalog
import dev.groknull.bpmner.rules.internal.domain.nlp.testBpmnNlp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Helper kit for per-rule tests. The contract is deliberately small — these are functions a
 * future rule author can read and copy without learning a framework.
 *
 *  - [loadRule] returns the production `BpmnRule` instance from a shared [PklRuleCatalog].
 *    The rule's pattern, vocabularies, target elements, and severity come from the actual
 *    `linter/pkl/rules/<Name>.pkl` file, NOT a synthetic Kotlin config. This is the
 *    consistency point: every per-rule test exercises what production exercises.
 *  - [context] builds a [BpmnDefinitionContext] from a list of nodes (and optional explicit
 *    edges); auto-chains nodes when edges are omitted. Covers PropertyPattern, Topology,
 *    Connectivity, Pairing, and Vocabulary primitives.
 *  - [assertFires] / [assertSilent] / [assertDiagnostic] are the standard assertion shapes.
 *
 * Pool-aware contexts are deliberately not included here — the `BpmnDefinition` model has no
 * pool/lane vocabulary today. Pool support arrives with the corresponding vocabulary work;
 * extend this kit then.
 */
internal object PklRuleTestSupport {
    // The catalog evaluates Pkl at init (~1s). Shared across the JVM so per-rule test classes
    // don't each pay that cost; the catalog is immutable post-init, so sharing is safe.
    private val catalog: PklRuleCatalog by lazy { PklRuleCatalog(emptyList(), testBpmnNlp()) }

    /**
     * Returns the production [BpmnRule] for [ruleId] (e.g. `"name-business-meaningful-label"`).
     * Fails the test with a clear message if the id doesn't resolve — usually because the rule
     * is still deferred (`checkPrimitive == null`) or its severity is `off` at load time.
     */
    fun loadRule(ruleId: String): BpmnRule = catalog.ruleById(ruleId)
        ?: error(
            "Pkl rule '$ruleId' is not in the active registry. " +
                "Check that the rule has a `checkPrimitive` and `severity != \"off\"` in its .pkl file.",
        )

    /**
     * Builds a [BpmnDefinitionContext] from a list of nodes. When [edges] is null, nodes are
     * chained in order (`f1: n[0]→n[1]`, `f2: n[1]→n[2]`, ...). Single-node fixtures get a
     * self-edge to satisfy `BpmnDefinition.sequences.isNotEmpty()`; use an explicit empty
     * `edges = emptyList()` only when the rule under test tolerates it.
     */
    fun context(
        nodes: List<BpmnNode>,
        edges: List<BpmnEdge>? = null,
        diagramCount: Int = 0,
    ): BpmnDefinitionContext {
        val actualEdges = edges ?: defaultEdges(nodes)
        return BpmnDefinitionContext(
            BpmnDefinition(
                processId = "P",
                processName = "Process",
                nodes = nodes,
                sequences = actualEdges,
                diagramCount = diagramCount,
            ),
        )
    }

    private fun defaultEdges(nodes: List<BpmnNode>): List<BpmnEdge> = when {
        nodes.size >= 2 -> nodes.zipWithNext().mapIndexed { idx, (a, b) -> BpmnEdge("f${idx + 1}", a.id, b.id) }

        // Single-node test fixture — emit a self-edge so the BpmnDefinition is structurally valid.
        nodes.isNotEmpty() -> listOf(BpmnEdge("f1", nodes.first().id, nodes.first().id))

        else -> error("context() requires at least one node")
    }

    /**
     * Asserts the rule fires on exactly the nodes whose ids are in [expectedElementIds] (order-
     * insensitive). When [expectedDiagnosticCode] is provided, every emitted diagnostic must
     * also pin to that code — use this for multi-code rules like Composite checks.
     */
    fun assertFires(
        rule: BpmnRule,
        ctx: BpmnDefinitionContext,
        expectedElementIds: Collection<String>,
        expectedDiagnosticCode: String? = null,
        message: String? = null,
    ) {
        val diagnostics = rule.evaluate(ctx)
        val actualIds = diagnostics.map { it.elementId }.toSet()
        val prefix = message?.let { "$it: " } ?: ""
        assertEquals(
            expectedElementIds.toSet(),
            actualIds,
            "${prefix}expected diagnostics on $expectedElementIds, got $actualIds. Full diagnostics: $diagnostics",
        )
        if (expectedDiagnosticCode != null) {
            val codes = diagnostics.map { it.diagnosticCode }.toSet()
            assertEquals(
                setOf(expectedDiagnosticCode),
                codes,
                "${prefix}expected all diagnostics to carry code '$expectedDiagnosticCode', got $codes",
            )
        }
    }

    /** Asserts the rule emits no diagnostics for the given context. */
    fun assertSilent(rule: BpmnRule, ctx: BpmnDefinitionContext, message: String? = null) {
        val diagnostics = rule.evaluate(ctx)
        assertTrue(
            diagnostics.isEmpty(),
            (message?.let { "$it: " } ?: "") + "expected no diagnostics; got $diagnostics",
        )
    }

    /**
     * Returns the rule's diagnostics for custom inspection. Use this when the test needs to
     * pin diagnostic metadata that [assertFires] doesn't cover (repair fields, message
     * substrings, full equality on a structured diagnostic).
     */
    fun evaluate(rule: BpmnRule, ctx: BpmnDefinitionContext): List<RuleDiagnostic> = rule.evaluate(ctx)
}
