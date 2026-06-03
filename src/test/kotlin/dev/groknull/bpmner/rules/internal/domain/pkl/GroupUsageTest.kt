/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.pkl

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnGroup
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.evaluate
import dev.groknull.bpmner.rules.internal.domain.pkl.PklRuleTestSupport.loadRule
import kotlin.test.Test
import kotlin.test.assertEquals

internal class GroupUsageTest {
    private val rule = loadRule("art-group-usage")

    @Test
    fun `GroupUsage is active and emits info diagnostic for each group`() {
        val diagnostics = evaluate(
            rule,
            BpmnDefinitionContext(
                BpmnDefinition(
                    processId = "P",
                    processName = "Process",
                    nodes = listOf(BpmnStartEvent("s", "Start"), BpmnEndEvent("e", "End")),
                    sequences = listOf(BpmnEdge("f", "s", "e")),
                    groups = listOf(BpmnGroup("g1", "Review"), BpmnGroup("g2")),
                ),
            ),
        )

        assertEquals(listOf("g1", "g2"), diagnostics.map { it.elementId })
        assertEquals(setOf("info"), diagnostics.map { it.severity.name.lowercase() }.toSet())
    }
}
