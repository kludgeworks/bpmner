/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
import org.springframework.stereotype.Component

/**
 * Flags `isDefault` sequence flows whose source is not a `BpmnExclusiveGateway`, and any
 * source node that has more than one default flow. Ports
 * `BpmnDefinitionValidator.validateDefaultFlows` with byte-identical messages.
 *
 * An orphan `isDefault` edge (sourceRef points to no node) is also invalid here — the
 * separate [DanglingEdgeRule] surfaces the missing-sourceRef issue, but this rule still
 * owns the "isDefault is only valid on EXCLUSIVE_GATEWAY" guarantee and fires on the
 * orphan case to stay complete.
 */
@Component
internal class DefaultFlowRule : BpmnRule {
    override val id: String = "def-default-flows"

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.defaultsBySource.forEach { (sourceId, defaults) ->
            val source = ctx.nodesById[sourceId]
            if (source == null || source !is BpmnExclusiveGateway) {
                defaults.forEach { edge ->
                    // TODO(#233): after #216 retires parity, render blank edge IDs as "<blank>" to
                    //  match DanglingEdgeRule's convention — currently we faithfully reproduce the
                    //  legacy validator's double-space gap rendering for parity.
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-default-flow-non-gateway",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "edge ${edge.id} isDefault is only valid when sourceRef points to an EXCLUSIVE_GATEWAY",
                            elementId = edge.id.ifBlank { null },
                        )
                }
            }
            if (defaults.size > 1) {
                // TODO(#233): after #216 retires parity, map blank ids in the join to "<blank>".
                val ids = defaults.joinToString(", ") { it.id }
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-multiple-default-flows",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message = "node $sourceId has ${defaults.size} default flows ($ids); at most one is allowed",
                        elementId = sourceId,
                    )
            }
        }

        return diagnostics
    }
}
