/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnBusinessRuleTask
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnReceiveTask
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.BpmnSendTask
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
import org.springframework.stereotype.Component

/**
 * Flags task-payload fields that are blank or reference an unresolved catalog entry.
 * Ports `BpmnDefinitionValidator.validateTaskPayloads` with byte-identical messages.
 *
 * - `BpmnSendTask` / `BpmnReceiveTask` require a non-blank `messageRef` that resolves to
 *   an entry in the process-level message catalog.
 * - `BpmnBusinessRuleTask` requires a non-blank `decisionRef`. No decision catalog exists
 *   today (cf. #196 cross-cutting), so only the non-blank check fires; catalog resolution
 *   lands when the typed decision catalog does.
 */
@Component
internal class TaskPayloadRule : BpmnRule {
    override val id: String = "def-task-payloads"

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.definition.nodes.forEach { node ->
            when (node) {
                is BpmnSendTask -> {
                    validateMessageRef(node.id, "sendTask", node.messageRef, ctx.messageIds, diagnostics)
                }

                is BpmnReceiveTask -> {
                    validateMessageRef(node.id, "receiveTask", node.messageRef, ctx.messageIds, diagnostics)
                }

                is BpmnBusinessRuleTask -> {
                    if (node.decisionRef.isBlank()) {
                        diagnostics +=
                            RuleDiagnostic(
                                diagnosticCode = "def-missing-decision-ref",
                                ruleId = id,
                                severity = RuleSeverity.ERROR,
                                message = "businessRuleTask ${node.id} is missing the required decisionRef attribute",
                                elementId = node.id,
                            )
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        return diagnostics
    }

    private fun validateMessageRef(
        nodeId: String,
        elementName: String,
        messageRef: String,
        messageIds: Set<String>,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (messageRef.isBlank()) {
            diagnostics +=
                RuleDiagnostic(
                    diagnosticCode = "def-missing-message-ref",
                    ruleId = id,
                    severity = RuleSeverity.ERROR,
                    message = "$elementName $nodeId is missing the required messageRef attribute",
                    elementId = nodeId,
                )
        } else if (messageRef !in messageIds) {
            diagnostics +=
                RuleDiagnostic(
                    diagnosticCode = "def-invalid-task-message-ref",
                    ruleId = id,
                    severity = RuleSeverity.ERROR,
                    message = "$elementName $nodeId messageRef '$messageRef' does not match any message catalog id",
                    elementId = nodeId,
                )
        }
    }
}
