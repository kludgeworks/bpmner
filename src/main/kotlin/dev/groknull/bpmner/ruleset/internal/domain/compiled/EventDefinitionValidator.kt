/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.compiled

import dev.groknull.bpmner.bpmn.BpmnDefinitionContext
import dev.groknull.bpmner.bpmn.BpmnErrorEventDefinition
import dev.groknull.bpmner.bpmn.BpmnEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTerminateEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTimerEventDefinition
import dev.groknull.bpmner.bpmn.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.ruleset.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_ERROR_REF
import dev.groknull.bpmner.ruleset.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_MESSAGE_REF

internal class EventDefinitionValidator(
    private val ruleId: String,
    private val ctx: BpmnDefinitionContext,
) {
    fun validate(nodeId: String, eventDefinition: BpmnEventDefinition): List<RuleDiagnostic> = when (eventDefinition) {
        is BpmnNoneEventDefinition,
        is BpmnTerminateEventDefinition,
        -> emptyList()

        is BpmnTimerEventDefinition -> validateTimerEventDefinition(nodeId, eventDefinition)

        is BpmnMessageEventDefinition -> validateMessageEventDefinition(nodeId, eventDefinition)

        is BpmnErrorEventDefinition -> validateErrorEventDefinition(nodeId, eventDefinition)

        // Unrecognized event definitions have no fields this validator can check; the
        // `BpmnSubset` rule flags them.
        is BpmnUnrecognizedEventDefinition -> emptyList()

        else -> emptyList()
    }

    private fun validateTimerEventDefinition(
        nodeId: String,
        eventDefinition: BpmnTimerEventDefinition,
    ): List<RuleDiagnostic> {
        return if (eventDefinition.expression.isBlank()) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = "def-missing-timer-expr",
                    nodeId = nodeId,
                    message = "event $nodeId timer definition expression must not be blank",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun validateMessageEventDefinition(
        nodeId: String,
        eventDefinition: BpmnMessageEventDefinition,
    ): List<RuleDiagnostic> {
        return if (eventDefinition.messageRef.isBlank()) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_MESSAGE_REF,
                    nodeId = nodeId,
                    message = "event $nodeId messageEventDefinition is missing the required messageRef attribute",
                ),
            )
        } else if (eventDefinition.messageRef !in ctx.messageIds) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_MESSAGE_REF,
                    nodeId = nodeId,
                    message = "event $nodeId messageRef '${eventDefinition.messageRef}' does not match any message catalog id",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun validateErrorEventDefinition(
        nodeId: String,
        eventDefinition: BpmnErrorEventDefinition,
    ): List<RuleDiagnostic> {
        return if (eventDefinition.errorRef.isBlank()) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_ERROR_REF,
                    nodeId = nodeId,
                    message = "event $nodeId errorEventDefinition is missing the required errorRef attribute",
                ),
            )
        } else if (eventDefinition.errorRef !in ctx.errorIds) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_ERROR_REF,
                    nodeId = nodeId,
                    message = "event $nodeId errorRef '${eventDefinition.errorRef}' does not match any error catalog id",
                ),
            )
        } else {
            emptyList()
        }
    }

    private fun eventDiagnostic(
        diagnosticCode: String,
        nodeId: String,
        message: String,
    ): RuleDiagnostic = RuleDiagnostic(
        diagnosticCode = diagnosticCode,
        ruleId = ruleId,
        severity = RuleSeverity.ERROR,
        message = message,
        elementId = nodeId,
    )
}
