/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnGroup
import dev.groknull.bpmner.core.BpmnNode

/**
 * Shared test helpers for primitive rule tests.
 * Extracted to eliminate CPD violations across DeterministicPrimitivesTest, PropertyPatternCheckTest,
 * and NlpPrimitivesTest.
 *
 * These are package-level functions so test classes can call them without qualification —
 * removing the identical `private fun metadata/context` from each class is sufficient.
 */

/** Builds a minimal [RuleMetadata] for testing — id is used as name, slug, and error prefix. */
internal fun metadata(
    id: String,
    vararg targetElements: String,
): RuleMetadata = RuleMetadata(
    id = id,
    name = id,
    slug = id,
    category = RuleCategory.General,
    intent = "Test rule.",
    forModellers = "Test rule.",
    forAI = "Test rule.",
    targetElements = targetElements.toList(),
    errorMessages = mapOf("default" to "$id violation"),
    severity = RuleSeverity.ERROR,
)

/**
 * Builds a [BpmnDefinitionContext] from a list of nodes with auto-generated linear sequence edges,
 * or explicit [edges] if provided.
 */
internal fun context(
    nodes: List<BpmnNode>,
    edges: List<BpmnEdge>? = null,
    groups: List<BpmnGroup> = emptyList(),
): BpmnDefinitionContext {
    val actualEdges =
        edges ?: nodes.zipWithNext().mapIndexed { index, (source, target) ->
            BpmnEdge("f${index + 1}", source.id, target.id)
        }
    return BpmnDefinitionContext(
        BpmnDefinition(
            processId = "P",
            processName = "Process",
            nodes = nodes,
            sequences = actualEdges.ifEmpty { listOf(BpmnEdge("f", nodes.first().id, nodes.last().id)) },
            groups = groups,
        ),
    )
}
