/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnEndEvent
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.BpmnStartEvent
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import org.springframework.stereotype.Component

/**
 * Flags definitions that lack at least one start event or at least one end event. Ports
 * `BpmnDefinitionValidator.validateRequiredEvents` with byte-identical messages so the
 * #216 parity test sees the same output.
 *
 * Diagnostics are process-scoped — no specific `elementId` is set.
 */
@Component
internal class RequiredEventsRule : BpmnRule {
    override val id: String = "def-required-events"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Required Events",
        slug = "required-events",
        category = RuleCategory.Definition,
        intent = "Ensure each BPMN definition has at least one start event and one end event.",
        forModellers = "Model a clear process start and completion point.",
        forAI = "Include at least one START_EVENT and one END_EVENT in every generated definition.",
        targetElements = listOf("bpmn:StartEvent", "bpmn:EndEvent"),
        errorMessages =
        mapOf(
            "def-missing-start-event" to "Definition must contain at least one start event.",
            "def-missing-end-event" to "Definition must contain at least one end event.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        if (ctx.definition.nodes.none { it is BpmnStartEvent && it.parentRef == null }) {
            diagnostics +=
                RuleDiagnostic(
                    diagnosticCode = "def-missing-start-event",
                    ruleId = id,
                    severity = RuleSeverity.ERROR,
                    message = "definition must contain at least one START_EVENT",
                )
        }
        if (ctx.definition.nodes.none { it is BpmnEndEvent && it.parentRef == null }) {
            diagnostics +=
                RuleDiagnostic(
                    diagnosticCode = "def-missing-end-event",
                    ruleId = id,
                    severity = RuleSeverity.ERROR,
                    message = "definition must contain at least one END_EVENT",
                )
        }

        return diagnostics
    }
}
