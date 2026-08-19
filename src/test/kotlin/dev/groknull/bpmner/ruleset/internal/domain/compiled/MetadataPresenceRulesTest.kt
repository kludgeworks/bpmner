/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataPresenceRulesTest {
    @Test
    fun `header rule reports only when header is absent`() = assertPresence(HeaderPresentRule(), "bpmner-diagram-header")

    @Test
    fun `notes rule reports only when notes are absent`() = assertPresence(NotesPresentRule(), "bpmner-diagram-notes")

    @Test
    fun `legend rule reports only when legend is absent`() = assertPresence(LegendPresentRule(), "bpmner-diagram-legend")

    private fun assertPresence(rule: dev.groknull.bpmner.bpmn.BpmnRule, markerId: String) {
        assertEquals(1, rule.evaluate(context()).size)
        assertTrue(rule.evaluate(context(BpmnTextAnnotation(markerId, "metadata"))).isEmpty())
    }

    private fun context(vararg annotations: BpmnTextAnnotation) = BpmnDefinitionContext(
        BpmnDefinition(
            processId = "process",
            processName = "Process",
            nodes = emptyList(),
            sequences = emptyList(),
            annotations = annotations.toList(),
        ),
    )
}
