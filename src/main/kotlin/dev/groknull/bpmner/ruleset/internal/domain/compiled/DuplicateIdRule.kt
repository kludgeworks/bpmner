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
 * Flags duplicate node ids and duplicate sequence-flow (edge) ids. Ids are compared after
 * trimming whitespace — matches the legacy `BpmnDefinitionValidator.validateDuplicateIds`
 * behavior so the #216 parity test sees byte-identical messages.
 *
 * Cannot rely on `ctx.nodeIds` / `ctx.sequenceIds` because those are `Set`s that already
 * collapse duplicates; this rule walks the raw lists with its own `groupBy { it.id.trim() }`.
 */
@Component
internal class DuplicateIdRule : BpmnRule {
    override val id: String = "def-duplicate-ids"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Duplicate IDs",
        slug = "duplicate-ids",
        category = RuleCategory.Definition,
        intent = "Ensure node and sequence-flow identifiers are unique after trimming whitespace.",
        forModellers = "Give every element and flow a unique id.",
        forAI = "Generate unique ids for every node and sequenceFlow.",
        targetElements = listOf("bpmn:FlowNode", "bpmn:SequenceFlow"),
        errorMessages =
        mapOf(
            "def-duplicate-node-id" to "Node ids must be unique.",
            "def-duplicate-edge-id" to "Sequence flow ids must be unique.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.definition.nodes
            .map { it.id.trim() }
            .groupBy { it }
            .filter { (groupId, all) -> groupId.isNotBlank() && all.size > 1 }
            .keys
            .forEach { dupId ->
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-duplicate-node-id",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "duplicate node id: $dupId",
                        elementId = dupId,
                    )
            }

        ctx.definition.sequences
            .map { it.id.trim() }
            .groupBy { it }
            .filter { (groupId, all) -> groupId.isNotBlank() && all.size > 1 }
            .keys
            .forEach { dupId ->
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-duplicate-edge-id",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "duplicate edge id: $dupId",
                        elementId = dupId,
                    )
            }

        return diagnostics
    }
}
