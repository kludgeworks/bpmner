/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity
import org.springframework.stereotype.Component

// These marker IDs must stay in sync with MetadataSynthesis.
private const val HEADER_ID = "bpmner-diagram-header"
private const val NOTES_ID = "bpmner-diagram-notes"
private const val LEGEND_ID = "bpmner-diagram-legend"

@Component
internal class HeaderPresentRule : BpmnRule {
    override val id = "def-header-present"
    override val metadata = metadata(id, "Header Present", "Add a diagram header.")
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = diagnosticWhenAbsent(ctx, HEADER_ID, id, metadata)
}

@Component
internal class NotesPresentRule : BpmnRule {
    override val id = "def-notes-present"
    override val metadata = metadata(id, "Notes Present", "Add diagram notes.")
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = diagnosticWhenAbsent(ctx, NOTES_ID, id, metadata)
}

@Component
internal class LegendPresentRule : BpmnRule {
    override val id = "def-legend-present"
    override val metadata = metadata(id, "Legend Present", "Add a diagram legend.")
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = diagnosticWhenAbsent(ctx, LEGEND_ID, id, metadata)
}

private fun metadata(id: String, name: String, intent: String) = RuleMetadata(
    id = id,
    name = name,
    slug = id.removePrefix("def-"),
    category = RuleCategory.Definition,
    intent = intent,
    forModellers = intent,
    forAI = intent,
    targetElements = listOf("bpmn:TextAnnotation"),
    errorMessages = mapOf("default" to intent),
    severity = RuleSeverity.INFO,
    repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
)

private fun diagnosticWhenAbsent(
    ctx: BpmnDefinitionContext,
    markerId: String,
    ruleId: String,
    metadata: RuleMetadata,
): List<RuleDiagnostic> = if (ctx.definition.annotations.none { it.id == markerId }) {
    listOf(
        RuleDiagnostic(
            ruleId,
            ruleId,
            RuleSeverity.INFO,
            metadata.errorMessages.getValue("default"),
            ctx.definition.processId,
        ),
    )
} else {
    emptyList()
}
