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

/**
 * Flags sequence-flow edges whose `sourceRef` or `targetRef` does not resolve to a node in
 * the definition, and edges whose source and target are identical (self-references). Ports
 * `BpmnDefinitionValidator.validateEdges` with byte-identical messages.
 *
 * `elementId` is the edge id when present, `null` when the edge id is blank — matching the
 * legacy validator's behavior of inlining `"<blank>"` into the message but not pretending
 * the edge has a stable id.
 */
@Component
internal class DanglingEdgeRule : BpmnRule {
    override val id: String = "def-dangling-edges"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Dangling Edges",
        slug = "dangling-edges",
        category = RuleCategory.Definition,
        intent = "Ensure every sequence flow connects existing BPMN nodes and does not self-reference.",
        forModellers = "Connect each flow to two distinct elements that exist in the process.",
        forAI = "Validate sequenceFlow sourceRef and targetRef against node ids before returning BPMN.",
        targetElements = listOf("bpmn:SequenceFlow"),
        errorMessages =
        mapOf(
            "def-dangling-source" to "Sequence flow sourceRef must match an existing node id.",
            "def-dangling-target" to "Sequence flow targetRef must match an existing node id.",
            "def-self-reference" to "Sequence flow sourceRef and targetRef must be different.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.definition.sequences.forEach { edge ->
            val edgeLabel = edge.id.ifBlank { "<blank>" }
            val edgeElementId = edge.id.ifBlank { null }

            if (edge.sourceRef !in ctx.nodeIds) {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-dangling-source",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "edge $edgeLabel sourceRef '${edge.sourceRef}' does not match any node id",
                        elementId = edgeElementId,
                    )
            }
            if (edge.targetRef !in ctx.nodeIds) {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-dangling-target",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "edge $edgeLabel targetRef '${edge.targetRef}' does not match any node id",
                        elementId = edgeElementId,
                    )
            }
            if (edge.sourceRef == edge.targetRef) {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-self-reference",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "edge $edgeLabel must not self-reference source and target",
                        elementId = edgeElementId,
                    )
            }
        }

        return diagnostics
    }
}
