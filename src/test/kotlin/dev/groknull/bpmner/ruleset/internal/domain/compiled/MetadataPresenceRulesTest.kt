/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnTextAnnotation
import dev.groknull.bpmner.bpmn.DiagramMetadata
import dev.groknull.bpmner.bpmn.DiagramStatusColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetadataPresenceRulesTest {
    @Test
    fun `header rule reports missing and incomplete headers`() = assertPresence(
        HeaderPresentRule(),
        DiagramMetadata.HEADER_ID,
        DiagramMetadata.header("Process", "process"),
    )

    @Test
    fun `notes rule reports missing and incomplete notes`() = assertPresence(
        NotesPresentRule(),
        DiagramMetadata.NOTES_ID,
        DiagramMetadata.notes("Process", java.time.Instant.parse("2026-08-20T12:00:00Z")),
    )

    @Test
    fun `legend rule reports missing and incomplete legends`() = assertPresence(
        LegendPresentRule(),
        DiagramMetadata.LEGEND_ID,
        DiagramMetadata.legend(DiagramStatusColors("#1", "#2", "#3", "#4")),
    )

    private fun assertPresence(rule: dev.groknull.bpmner.bpmn.BpmnRule, markerId: String, validText: String) {
        assertEquals(1, rule.evaluate(context()).size)
        assertEquals(1, rule.evaluate(context(BpmnTextAnnotation(markerId, "metadata"))).size)
        assertTrue(rule.evaluate(context(BpmnTextAnnotation(markerId, validText))).isEmpty())
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
