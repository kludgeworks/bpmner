/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnExclusiveGateway
import dev.groknull.bpmner.api.BpmnInclusiveGateway
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import org.springframework.stereotype.Component

/**
 * Flags `isDefault` sequence flows whose source is neither a `BpmnExclusiveGateway` nor a
 * `BpmnInclusiveGateway`, and any source node that has more than one default flow. Ports
 * `BpmnDefinitionValidator.validateDefaultFlows` with byte-identical messages.
 *
 * An orphan `isDefault` edge (sourceRef points to no node) is also invalid here — the
 * separate [DanglingEdgeRule] surfaces the missing-sourceRef issue, but this rule still
 * owns the "isDefault is only valid on EXCLUSIVE_GATEWAY or INCLUSIVE_GATEWAY" guarantee
 * and fires on the orphan case to stay complete.
 */
@Component
internal class DefaultFlowRule : BpmnRule {
    override val id: String = "def-default-flows"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Default Flows",
        slug = "default-flows",
        category = RuleCategory.DEFINITION,
        intent =
        "Ensure BPMN default sequence flows are only used from exclusive or inclusive gateways" +
            " and are unique per source.",
        forModellers = "Use at most one default outgoing flow from an exclusive or inclusive gateway.",
        forAI = "Set isDefault only on a single outgoing flow from an exclusive or inclusive gateway.",
        targetElements = listOf("bpmn:SequenceFlow", "bpmn:ExclusiveGateway", "bpmn:InclusiveGateway"),
        errorMessages =
        mapOf(
            "def-default-flow-non-gateway" to "Default flow must originate from an exclusive or inclusive gateway.",
            "def-multiple-default-flows" to "A node can have at most one default flow.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.defaultsBySource.forEach { (sourceId, defaults) ->
            val source = ctx.nodesById[sourceId]
            if (source == null || (source !is BpmnExclusiveGateway && source !is BpmnInclusiveGateway)) {
                defaults.forEach { edge ->
                    // TODO(#233): after #216 retires parity, render blank edge IDs as "<blank>" to
                    //  match DanglingEdgeRule's convention — currently we faithfully reproduce the
                    //  legacy validator's double-space gap rendering for parity.
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-default-flow-non-gateway",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "edge ${edge.id} isDefault is only valid when sourceRef" +
                                " points to an EXCLUSIVE_GATEWAY or INCLUSIVE_GATEWAY",
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
