/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.groknull.bpmner.rules.internal.domain.compiled
import dev.groknull.bpmner.api.BpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnEndEvent
import dev.groknull.bpmner.api.BpmnEventDefinition
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.BpmnStartEvent
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
import dev.groknull.bpmner.api.RuleCategory
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.api.isTask
import org.springframework.stereotype.Component
/**
 * Validates the event-definition correctness invariants on every event-position node:
 *
 *  - Intermediate catch/throw and boundary events must declare a non-`NONE` event definition.
 *  - Boundary events must declare `attachedToRef`; the referenced node must exist and must
 *    be a task (boundary events attach to activities, not events or gateways).
 *  - Each typed event definition (timer / message / signal / error / escalation) is
 *    structurally valid — required fields non-blank and refs resolve to the matching
 *    process-level catalog.
 *
 * Ports `BpmnDefinitionValidator.validateEventDefinitions` + the inner
 * `validateEventDefinition` helper with byte-identical messages so the #216 parity test
 * sees the same output. Emits 9 distinct `diagnosticCode`s — Phase 2's severity-override
 * scheme keys on `diagnosticCode`, not `id`, so each check stays independently configurable
 * without splitting this into 9 separate rule classes.
 */
@Component
internal class EventDefinitionRule : BpmnRule {
    companion object {
        internal const val DEF_INVALID_MESSAGE_REF = "def-invalid-message-ref"
        internal const val DEF_INVALID_SIGNAL_REF = "def-invalid-signal-ref"
        internal const val DEF_INVALID_ERROR_REF = "def-invalid-error-ref"
        internal const val DEF_INVALID_ESCALATION_REF = "def-invalid-escalation-ref"
    }
    override val id: String = "def-event-definitions"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Event Definitions",
        slug = "event-definitions",
        category = RuleCategory.Definition,
        intent = "Ensure BPMN event definitions are present, structurally valid, and resolve to catalog entries.",
        forModellers = "Choose the correct event trigger and attach boundary events to activities.",
        forAI = "Populate event definitions and catalog refs consistently for every event node.",
        targetElements =
        listOf(
            "bpmn:StartEvent",
            "bpmn:EndEvent",
            "bpmn:IntermediateCatchEvent",
            "bpmn:IntermediateThrowEvent",
            "bpmn:BoundaryEvent",
        ),
        errorMessages =
        mapOf(
            "def-missing-event-def" to "Intermediate and boundary events must declare an event definition.",
            "def-missing-attached-to" to "Boundary events must declare attachedToRef.",
            "def-invalid-attached-to" to "Boundary event attachedToRef must match an existing node id.",
            "def-non-task-attached-to" to "Boundary events must attach to an activity.",
            "def-missing-timer-expr" to "Timer event expression must not be blank.",
            DEF_INVALID_MESSAGE_REF to "Message event definitions must reference an existing message.",
            DEF_INVALID_SIGNAL_REF to "Signal event definitions must reference an existing signal.",
            DEF_INVALID_ERROR_REF to "Error event definitions must reference an existing error.",
            DEF_INVALID_ESCALATION_REF to "Escalation event definitions must reference an existing escalation.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )
    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()
        val eventValidator = EventDefinitionValidator(id, ctx)
        ctx.definition.nodes.forEach { node ->
            when (node) {
                is BpmnStartEvent -> {
                    diagnostics += eventValidator.validate(node.id, node.eventDefinition)
                }

                is BpmnEndEvent -> {
                    diagnostics += eventValidator.validate(node.id, node.eventDefinition)
                }

                is BpmnIntermediateCatchEvent -> {
                    validateIntermediate("intermediate catch event", node.id, node.eventDefinition, eventValidator, diagnostics)
                }

                is BpmnIntermediateThrowEvent -> {
                    validateIntermediate("intermediate throw event", node.id, node.eventDefinition, eventValidator, diagnostics)
                }

                is BpmnBoundaryEvent -> {
                    validateBoundary(node, ctx, eventValidator, diagnostics)
                }

                else -> {
                    Unit
                }
            }
        }
        return diagnostics
    }
    private fun validateIntermediate(
        nodeLabel: String,
        nodeId: String,
        eventDefinition: BpmnEventDefinition,
        eventValidator: EventDefinitionValidator,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (eventDefinition is BpmnNoneEventDefinition) {
            diagnostics += missingEventDef(nodeLabel = nodeLabel, nodeId = nodeId)
        }
        diagnostics += eventValidator.validate(nodeId, eventDefinition)
    }
    private fun validateBoundary(
        node: BpmnBoundaryEvent,
        ctx: BpmnDefinitionContext,
        eventValidator: EventDefinitionValidator,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (node.eventDefinition is BpmnNoneEventDefinition) {
            diagnostics += missingEventDef(nodeLabel = "boundary event", nodeId = node.id)
        }
        validateAttachedToRef(node, ctx, diagnostics)
        diagnostics += eventValidator.validate(node.id, node.eventDefinition)
    }
    private fun validateAttachedToRef(
        node: BpmnBoundaryEvent,
        ctx: BpmnDefinitionContext,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (node.attachedToRef.isBlank()) {
            diagnostics +=
                RuleDiagnostic(
                    diagnosticCode = "def-missing-attached-to",
                    ruleId = id,
                    severity = RuleSeverity.ERROR,
                    message = "boundary event ${node.id} is missing the required attachedToRef attribute",
                    elementId = node.id,
                )
            return
        }
        val attachedTo = ctx.nodesById[node.attachedToRef]
        when {
            attachedTo == null -> {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-invalid-attached-to",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message =
                        "boundary event ${node.id} attachedToRef '${node.attachedToRef}' " +
                            "does not match any node id",
                        elementId = node.id,
                    )
            }

            !attachedTo.isTask() -> {
                diagnostics +=
                    RuleDiagnostic(
                        diagnosticCode = "def-non-task-attached-to",
                        ruleId = id,
                        severity = RuleSeverity.ERROR,
                        message =
                        "boundary event ${node.id} attachedToRef '${node.attachedToRef}' " +
                            "must reference an attachable activity",
                        elementId = node.id,
                    )
            }
        }
    }

    private fun missingEventDef(
        nodeLabel: String,
        nodeId: String,
    ): RuleDiagnostic = RuleDiagnostic(
        diagnosticCode = "def-missing-event-def",
        ruleId = id,
        severity = RuleSeverity.ERROR,
        message = "$nodeLabel $nodeId must declare an event definition",
        elementId = nodeId,
    )
}
