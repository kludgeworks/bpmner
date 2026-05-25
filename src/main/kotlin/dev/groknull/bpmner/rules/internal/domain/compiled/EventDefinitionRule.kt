/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnBoundaryEvent
import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnEndEvent
import dev.groknull.bpmner.api.BpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEventDefinition
import dev.groknull.bpmner.api.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.api.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.api.BpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnRule
import dev.groknull.bpmner.api.BpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnStartEvent
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairMetadata
import dev.groknull.bpmner.api.RepairSafety
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
    override val id: String = "def-event-definitions"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Event Definitions",
        slug = "event-definitions",
        category = "Definition",
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
            "def-invalid-message-ref" to "Message event definitions must reference an existing message.",
            "def-invalid-signal-ref" to "Signal event definitions must reference an existing signal.",
            "def-invalid-error-ref" to "Error event definitions must reference an existing error.",
            "def-invalid-escalation-ref" to "Escalation event definitions must reference an existing escalation.",
        ),
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(kind = RepairKind.LLM_MODEL_PATCH, safety = RepairSafety.LLM_ONLY),
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        val diagnostics = mutableListOf<RuleDiagnostic>()

        ctx.definition.nodes.forEach { node ->
            when (node) {
                is BpmnStartEvent -> {
                    validateEventDefinition(node.id, node.eventDefinition, ctx, diagnostics)
                }

                is BpmnEndEvent -> {
                    validateEventDefinition(node.id, node.eventDefinition, ctx, diagnostics)
                }

                is BpmnIntermediateCatchEvent -> {
                    validateIntermediate("intermediate catch event", node.id, node.eventDefinition, ctx, diagnostics)
                }

                is BpmnIntermediateThrowEvent -> {
                    validateIntermediate("intermediate throw event", node.id, node.eventDefinition, ctx, diagnostics)
                }

                is BpmnBoundaryEvent -> {
                    validateBoundary(node, ctx, diagnostics)
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
        ctx: BpmnDefinitionContext,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (eventDefinition is BpmnNoneEventDefinition) {
            diagnostics += missingEventDef(nodeLabel = nodeLabel, nodeId = nodeId)
        }
        validateEventDefinition(nodeId, eventDefinition, ctx, diagnostics)
    }

    private fun validateBoundary(
        node: BpmnBoundaryEvent,
        ctx: BpmnDefinitionContext,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        if (node.eventDefinition is BpmnNoneEventDefinition) {
            diagnostics += missingEventDef(nodeLabel = "boundary event", nodeId = node.id)
        }
        validateAttachedToRef(node, ctx, diagnostics)
        validateEventDefinition(node.id, node.eventDefinition, ctx, diagnostics)
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

    // Flat dispatcher over the 7-arm sealed BpmnEventDefinition hierarchy. Each arm is small
    // (a blank check + an optional catalog-membership check), but the cyclomatic + length
    // thresholds key on arm count, not per-arm complexity. Splitting into 4 per-type helpers
    // (one per typed catalog) would inflate signature noise without changing testability.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun validateEventDefinition(
        nodeId: String,
        eventDefinition: BpmnEventDefinition,
        ctx: BpmnDefinitionContext,
        diagnostics: MutableList<RuleDiagnostic>,
    ) {
        when (eventDefinition) {
            is BpmnNoneEventDefinition -> {
                Unit
            }

            is BpmnTerminateEventDefinition -> {
                Unit
            }

            is BpmnTimerEventDefinition -> {
                if (eventDefinition.expression.isBlank()) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-missing-timer-expr",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId timer definition expression must not be blank",
                            elementId = nodeId,
                        )
                }
            }

            is BpmnMessageEventDefinition -> {
                if (eventDefinition.messageRef.isBlank()) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-message-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId messageEventDefinition is missing the required messageRef attribute",
                            elementId = nodeId,
                        )
                } else if (eventDefinition.messageRef !in ctx.messageIds) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-message-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message =
                            "event $nodeId messageRef '${eventDefinition.messageRef}' " +
                                "does not match any message catalog id",
                            elementId = nodeId,
                        )
                }
            }

            is BpmnSignalEventDefinition -> {
                if (eventDefinition.signalRef.isBlank()) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-signal-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId signalEventDefinition is missing the required signalRef attribute",
                            elementId = nodeId,
                        )
                } else if (eventDefinition.signalRef !in ctx.signalIds) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-signal-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message =
                            "event $nodeId signalRef '${eventDefinition.signalRef}' " +
                                "does not match any signal catalog id",
                            elementId = nodeId,
                        )
                }
            }

            is BpmnErrorEventDefinition -> {
                if (eventDefinition.errorRef.isBlank()) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-error-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId errorEventDefinition is missing the required errorRef attribute",
                            elementId = nodeId,
                        )
                } else if (eventDefinition.errorRef !in ctx.errorIds) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-error-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId errorRef '${eventDefinition.errorRef}' does not match any error catalog id",
                            elementId = nodeId,
                        )
                }
            }

            is BpmnEscalationEventDefinition -> {
                if (eventDefinition.escalationRef.isBlank()) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-escalation-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message = "event $nodeId escalationEventDefinition is missing the required escalationRef attribute",
                            elementId = nodeId,
                        )
                } else if (eventDefinition.escalationRef !in ctx.escalationIds) {
                    diagnostics +=
                        RuleDiagnostic(
                            diagnosticCode = "def-invalid-escalation-ref",
                            ruleId = id,
                            severity = RuleSeverity.ERROR,
                            message =
                            "event $nodeId escalationRef '${eventDefinition.escalationRef}' " +
                                "does not match any escalation catalog id",
                            elementId = nodeId,
                        )
                }
            }
        }
    }
}
