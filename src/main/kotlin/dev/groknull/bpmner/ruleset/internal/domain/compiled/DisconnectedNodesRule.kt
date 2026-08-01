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
import dev.groknull.bpmner.ruleset.internal.domain.primitives.ConnectivityCheck
import dev.groknull.bpmner.ruleset.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.ruleset.internal.domain.primitives.ConnectivityMode
import org.springframework.stereotype.Component

@Component
internal class DisconnectedNodesRule : BpmnRule {
    override val id = "def-disconnected-nodes"
    override val metadata = RuleMetadata(
        id = id, name = "Disconnected Nodes", slug = "disconnected-nodes", category = RuleCategory.Definition,
        intent = "Ensure each process and subprocess flow scope forms one weakly connected component.",
        forModellers = "Connect all ordinary flow nodes within each process or subprocess scope.",
        forAI = "Do not leave isolated cycles or disconnected sequence-flow components in a scope.",
        targetElements = listOf("bpmn:FlowNode"),
        errorMessages = mapOf("default" to "Disconnected flow nodes"),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = ConnectivityCheck().evaluate(
        ctx,
        metadata,
        ConnectivityCheckConfig(ConnectivityMode.WEAK_COMPONENTS_BY_SCOPE),
    ).map { diagnostic ->
        val scope = diagnostic.elementId?.let { if (it == "process") "process" else "subprocess '$it'" } ?: "process"
        diagnostic.copy(
            message = "$scope contains disconnected flow nodes: ${diagnostic.message.substringAfter(": ")}",
            elementId = null,
        )
    }
}
