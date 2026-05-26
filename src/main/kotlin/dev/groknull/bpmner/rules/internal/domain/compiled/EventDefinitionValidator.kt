package dev.groknull.bpmner.rules.internal.domain.compiled

import dev.groknull.bpmner.api.BpmnDefinitionContext
import dev.groknull.bpmner.api.BpmnErrorEventDefinition
import dev.groknull.bpmner.api.BpmnEscalationEventDefinition
import dev.groknull.bpmner.api.BpmnEventDefinition
import dev.groknull.bpmner.api.BpmnMessageEventDefinition
import dev.groknull.bpmner.api.BpmnNoneEventDefinition
import dev.groknull.bpmner.api.BpmnSignalEventDefinition
import dev.groknull.bpmner.api.BpmnTerminateEventDefinition
import dev.groknull.bpmner.api.BpmnTimerEventDefinition
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_ERROR_REF
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_ESCALATION_REF
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_MESSAGE_REF
import dev.groknull.bpmner.rules.internal.domain.compiled.EventDefinitionRule.Companion.DEF_INVALID_SIGNAL_REF

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
        is BpmnSignalEventDefinition -> validateSignalEventDefinition(nodeId, eventDefinition)
        is BpmnErrorEventDefinition -> validateErrorEventDefinition(nodeId, eventDefinition)
        is BpmnEscalationEventDefinition -> validateEscalationEventDefinition(nodeId, eventDefinition)
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

    private fun validateSignalEventDefinition(
        nodeId: String,
        eventDefinition: BpmnSignalEventDefinition,
    ): List<RuleDiagnostic> {
        return if (eventDefinition.signalRef.isBlank()) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_SIGNAL_REF,
                    nodeId = nodeId,
                    message = "event $nodeId signalEventDefinition is missing the required signalRef attribute",
                ),
            )
        } else if (eventDefinition.signalRef !in ctx.signalIds) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_SIGNAL_REF,
                    nodeId = nodeId,
                    message = "event $nodeId signalRef '${eventDefinition.signalRef}' does not match any signal catalog id",
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

    private fun validateEscalationEventDefinition(
        nodeId: String,
        eventDefinition: BpmnEscalationEventDefinition,
    ): List<RuleDiagnostic> {
        return if (eventDefinition.escalationRef.isBlank()) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_ESCALATION_REF,
                    nodeId = nodeId,
                    message = "event $nodeId escalationEventDefinition is missing the required escalationRef attribute",
                ),
            )
        } else if (eventDefinition.escalationRef !in ctx.escalationIds) {
            listOf(
                eventDiagnostic(
                    diagnosticCode = DEF_INVALID_ESCALATION_REF,
                    nodeId = nodeId,
                    message = "event $nodeId escalationRef '${eventDefinition.escalationRef}' does not match any escalation catalog id",
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
