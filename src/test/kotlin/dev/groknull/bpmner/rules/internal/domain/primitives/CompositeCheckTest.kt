/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("MaxLineLength", "TooManyFunctions")

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompositeCheckTest {
    private val check = CompositeCheck()

    @Test
    fun `no ops when subChecks is empty`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "Task"), BpmnEndEvent("e", "End")))
        val diagnostics = check.evaluate(ctx, compositeMetadata(), CompositeCheckConfig(subChecks = emptyList()))
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `delegates to sub-checks and attributes each diagnostic to its declared code`() {
        // A BoundaryEventConstraints-style decomposition. We use existing primitives over
        // bpmn:UserTask so the test stays self-contained — pools/lanes/boundary events would
        // require richer fixtures than this test needs.
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUserTask("blank", ""),
                BpmnUserTask("typed", "Approve Activity"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val metadata = compositeMetadata(
            errorMessages = mapOf(
                "blank-name" to "Task name must not be blank",
                "type-word" to "Task name must not contain a type word",
                "default" to "Composite violation",
            ),
        )
        val config = CompositeCheckConfig(
            subChecks = listOf(
                SubCheckConfig(
                    diagnosticCode = "blank-name",
                    config = RequiredPropertyCheckConfig("name"),
                ),
                SubCheckConfig(
                    diagnosticCode = "type-word",
                    config = VocabularyCheckConfig(
                        property = "name",
                        mode = VocabularyMode.FORBID,
                        words = listOf("Activity"),
                    ),
                ),
            ),
        )

        val diagnostics = check.evaluate(ctx, metadata, config)

        val codes = diagnostics.map { it.diagnosticCode to it.elementId }.toSet()
        assertEquals(
            setOf("blank-name" to "blank", "type-word" to "typed"),
            codes,
            "Each sub-check's diagnostic must use the declared diagnosticCode and elementId",
        )
    }

    @Test
    fun `targetTypes prefilter narrows the effective scope of sub-checks`() {
        // The composite is targeted at FlowNode but the sub-check should only see UserTasks.
        val ctx = context(
            nodes = listOf(
                BpmnStartEvent("s", "Start"), // would match RequiredPropertyCheck on a wider scope, but excluded by targetTypes
                BpmnUserTask("blank", ""),
                BpmnUserTask("named", "Approve"),
                BpmnEndEvent("e", "End"),
            ),
        )
        val metadata = compositeMetadata(
            targetElements = listOf("bpmn:FlowNode"),
            errorMessages = mapOf("blank-name" to "Task name must not be blank"),
        )
        val config = CompositeCheckConfig(
            targetTypes = listOf("bpmn:UserTask"),
            subChecks = listOf(
                SubCheckConfig("blank-name", RequiredPropertyCheckConfig("name")),
            ),
        )

        val diagnostics = check.evaluate(ctx, metadata, config)

        assertEquals(listOf("blank"), diagnostics.map { it.elementId })
    }

    @Test
    fun `emits rule-config-error when a sub-check's diagnosticCode is not in parent errorMessages`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "Task"), BpmnEndEvent("e", "End")))
        val metadata = compositeMetadata(errorMessages = mapOf("default" to "fallback"))
        val config = CompositeCheckConfig(
            subChecks = listOf(
                SubCheckConfig(
                    diagnosticCode = "nonexistent-code",
                    config = RequiredPropertyCheckConfig("name"),
                ),
            ),
        )

        val diagnostics = check.evaluate(ctx, metadata, config)

        assertEquals(1, diagnostics.size)
        assertEquals("rule-config-error", diagnostics.single().diagnosticCode)
        assertTrue(diagnostics.single().message.contains("nonexistent-code"))
    }

    @Test
    fun `emits rule-config-error when a sub-check uses the reserved 'default' diagnosticCode`() {
        val ctx = context(nodes = listOf(BpmnStartEvent("s", "Start"), BpmnUserTask("t", "Task"), BpmnEndEvent("e", "End")))
        val metadata = compositeMetadata(errorMessages = mapOf("default" to "fallback"))
        val config = CompositeCheckConfig(
            subChecks = listOf(
                SubCheckConfig(
                    diagnosticCode = "default",
                    config = RequiredPropertyCheckConfig("name"),
                ),
            ),
        )

        val diagnostics = check.evaluate(ctx, metadata, config)

        assertEquals("rule-config-error", diagnostics.single().diagnosticCode)
        assertTrue(diagnostics.single().message.contains("\"default\""))
    }

    // The previous tests `emits rule-config-error when a sub-check tries to nest CompositeCheck`
    // and `emits rule-config-error when a sub-check tries to embed LlmCheckRule` exercised
    // runtime rejection. After PR #249 review, `SubCheckConfig.config` is typed as the
    // sealed `DeterministicCheckConfig` — `CompositeCheckConfig` and `LlmCheckRuleConfig`
    // don't implement it, so both nesting attempts now fail to compile. The Kotlin type
    // system is the test; no runtime assertion is needed.

    private fun compositeMetadata(
        id: String = "composite",
        targetElements: List<String> = listOf("bpmn:UserTask"),
        errorMessages: Map<String, String> = mapOf("default" to "composite violation"),
    ): RuleMetadata = RuleMetadata(
        id = id,
        name = id,
        slug = id,
        category = "Test",
        intent = "Test composite.",
        forModellers = "Test composite.",
        forAI = "Test composite.",
        targetElements = targetElements,
        errorMessages = errorMessages,
        severity = RuleSeverity.ERROR,
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
