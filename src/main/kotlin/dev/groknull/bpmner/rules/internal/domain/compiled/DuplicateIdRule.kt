/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
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
