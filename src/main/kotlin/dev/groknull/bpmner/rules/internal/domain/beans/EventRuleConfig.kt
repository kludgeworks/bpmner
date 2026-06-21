/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.beans

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RepairKind
import dev.groknull.bpmner.bpmn.RepairMetadata
import dev.groknull.bpmner.bpmn.RepairSafety
import dev.groknull.bpmner.bpmn.RuleCategory
import dev.groknull.bpmner.bpmn.RuleSeverity
import dev.groknull.bpmner.rules.internal.domain.compositeRule
import dev.groknull.bpmner.rules.internal.domain.nlp.BpmnNlp
import dev.groknull.bpmner.rules.internal.domain.primitiveRule
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShape
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShapeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingMode
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@Suppress("MaxLineLength")
internal class EventRuleConfig {
    companion object {
        // DSL string literals shared across multiple @Bean methods in this class.
        private const val BPMN_BOUNDARY_EVENT = "bpmn:BoundaryEvent"
        private const val BPMN_START_EVENT = "bpmn:StartEvent"
        private const val BPMN_INTERMEDIATE_CATCH = "bpmn:IntermediateCatchEvent"
        private const val BPMN_INTERMEDIATE_THROW = "bpmn:IntermediateThrowEvent"
        private const val BPMN_END_EVENT = "bpmn:EndEvent"
        private val ALL_EVENT_TYPES = listOf(
            BPMN_START_EVENT,
            BPMN_INTERMEDIATE_CATCH,
            BPMN_INTERMEDIATE_THROW,
            BPMN_END_EVENT,
        )
        private val INTERMEDIATE_EVENTS = listOf(BPMN_INTERMEDIATE_CATCH, BPMN_INTERMEDIATE_THROW)
    }

    @Bean
    fun evtBoundaryEventConstraints(nlp: BpmnNlp): BpmnRule = compositeRule(
        name = "Boundary Event Constraints",
        category = RuleCategory.Event,
        intent = "Enforce valid boundary event attachment and flow cardinality.",
        forModellers = "Attach boundary events to tasks or subprocesses, do not give them incoming sequence flow, and use exactly one outgoing sequence flow.",
        forAI = "Detect detached boundary events, boundary events with incoming flow or wrong outgoing count, and non-interrupting error boundary events.",
        targetElements = listOf(BPMN_BOUNDARY_EVENT),
        errorMessages = mapOf(
            "detached" to "Boundary event must be attached to a task or subprocess",
            "incoming" to "Boundary event must not have incoming sequence flow",
            "outgoing" to "Boundary event must have exactly one outgoing sequence flow",
            "errorInterrupting" to "Error boundary event must be interrupting",
            "default" to "Boundary event violates attachment or flow constraints",
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    ) {
        sub(
            "detached",
            ElementConstraintCheckConfig(
                element = BPMN_BOUNDARY_EVENT,
                mode = ElementConstraintMode.BOUNDARY_ATTACHED,
            ),
        )
        sub(
            "incoming",
            ConnectivityCheckConfig(mode = ConnectivityMode.NO_INCOMING),
        )
        sub(
            "outgoing",
            ElementConstraintCheckConfig(
                element = BPMN_BOUNDARY_EVENT,
                mode = ElementConstraintMode.BOUNDARY_SINGLE_OUTGOING,
            ),
        )
        sub(
            "errorInterrupting",
            ElementConstraintCheckConfig(
                element = BPMN_BOUNDARY_EVENT,
                mode = ElementConstraintMode.BOUNDARY_ERROR_INTERRUPTING,
            ),
        )
    }

    @Bean
    fun evtEventStateName(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Event State Name",
        category = RuleCategory.Event,
        intent = "Encourage event labels to describe states or happenings rather than process actions.",
        forModellers = "Name events as things that happen or states that are reached, not as actions performed by the process.",
        forAI = "Detect event labels whose first token is POS-tagged as a verb and suggest state-style wording.",
        targetElements = ALL_EVENT_TYPES,
        errorMessages = mapOf(
            "default" to "Event name should describe a state/happening, not an action",
        ),
        check = GrammaticalShapeCheckConfig(
            property = "name",
            mode = GrammaticalShape.STATE_LABEL,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun evtEventStatePattern(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Event State Pattern",
        category = RuleCategory.Event,
        intent = "Encourage event labels to follow noun plus state or result wording.",
        forModellers = "Name events with a noun and a clear resulting state, such as Request approved or Order received.",
        forAI = "Detect event labels that lack both a noun or proper noun and a state-like token such as an adjective or past participle.",
        targetElements = ALL_EVENT_TYPES,
        errorMessages = mapOf(
            "default" to "Event name should follow a noun + state/result pattern (e.g. Request approved)",
        ),
        check = GrammaticalShapeCheckConfig(
            property = "name",
            mode = GrammaticalShape.STATE_LABEL,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun evtErrorEndBoundaryPair(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Error End Boundary Pair",
        category = RuleCategory.Event,
        intent = "Ensure error end events propagate to matching parent boundary error handlers.",
        forModellers = "Place error end events inside subprocesses and provide a matching error boundary event on the parent subprocess.",
        forAI = "Detect error end events outside subprocesses or without a matching parent boundary error event using the same error name or code.",
        targetElements = listOf(BPMN_END_EVENT),
        errorMessages = mapOf(
            "outsideSubprocess" to "Error end event must be placed inside a subprocess",
            "missingBoundary" to "Error end event must match an error boundary event on its parent subprocess",
            "default" to "Error end event must match an error boundary event on its parent subprocess",
        ),
        check = PairingCheckConfig(mode = PairingMode.ERROR_END_BOUNDARY),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun evtIntermediateEventNotAction(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Intermediate Event Not Action",
        category = RuleCategory.Event,
        intent = "Ensure intermediate events describe states or happenings rather than work.",
        forModellers = "Use intermediate events for things that happen while activities perform the work.",
        forAI = "Detect intermediate catch or throw event labels that start with a verb or auxiliary and suggest state-style wording or a task.",
        targetElements = INTERMEDIATE_EVENTS,
        errorMessages = mapOf(
            "default" to "Intermediate event name should describe a state, not an action",
        ),
        check = GrammaticalShapeCheckConfig(
            property = "name",
            mode = GrammaticalShape.STATE_LABEL,
        ),
        nlp = nlp,
        severity = RuleSeverity.WARNING,
    )

    @Bean
    fun evtLinkEventPairing(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Link Event Pairing",
        category = RuleCategory.Event,
        intent = "Ensure link intermediate events are named and paired correctly.",
        forModellers = "Use throwing and catching link intermediate events in matched pairs with the same reference name in the same scope.",
        forAI = "Detect link events without names or without a named throw/catch counterpart in the same scope.",
        targetElements = INTERMEDIATE_EVENTS,
        errorMessages = mapOf(
            "missingName" to "Link event must have a name and a matching pair in the same scope",
            "missingCounterpart" to "Link event must have a named throw/catch counterpart in the same scope",
            "default" to "Link event must have a named throw/catch counterpart in the same scope",
        ),
        check = PairingCheckConfig(mode = PairingMode.LINK_PAIRING),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun evtMessageStartHasMessageFlow(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Message Start Has Message Flow",
        category = RuleCategory.Event,
        intent = "Ensure message-start semantics are modeled as inter-pool communication.",
        forModellers = "When a process starts through a message start event, model the incoming message flow from the external participant.",
        forAI = "Detect message start events that do not have an incoming message flow from another pool.",
        targetElements = listOf(BPMN_START_EVENT),
        errorMessages = mapOf(
            "default" to "Message start event must have an incoming message flow from another pool",
        ),
        check = PairingCheckConfig(mode = PairingMode.MESSAGE_START_FLOW),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )

    @Bean
    fun evtStartNoIncoming(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Start No Incoming",
        category = RuleCategory.Event,
        intent = "Enforce BPMN start-event structure.",
        forModellers = "Start events initiate the process and must not have incoming sequence flows.",
        forAI = "Validate that every start event has zero incoming sequence flows.",
        targetElements = listOf(BPMN_START_EVENT),
        errorMessages = mapOf(
            "default" to "Start event must not have incoming sequence flow",
        ),
        check = ConnectivityCheckConfig(mode = ConnectivityMode.NO_INCOMING),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
        repair = RepairMetadata(
            kind = RepairKind.LOCAL_MODEL_FIX,
            safety = RepairSafety.SAFE_AUTOMATIC,
            handler = "deleteIncomingFlows",
        ),
    )

    @Bean
    fun evtTimerStartEventsBlockUntilTime(nlp: BpmnNlp): BpmnRule = primitiveRule(
        name = "Timer Start Events Block Until Time",
        category = RuleCategory.Event,
        intent = "Ensure timer start events define the time condition that starts the process.",
        forModellers = "Use a timer start event only when the process waits for a specific date, duration, or cycle before starting.",
        forAI = "Detect timer start events with no timer expression or with more than one timer expression. General start-event incoming-flow checks are handled by the start-no-incoming rule.",
        targetElements = listOf(BPMN_START_EVENT),
        errorMessages = mapOf(
            "default" to "Timer start event must define exactly one timer expression",
            "missingTimerExpression" to "Timer start event must define a date, duration, or cycle",
            "multipleTimerExpressions" to "Timer start event must define only one timer expression",
        ),
        check = ElementConstraintCheckConfig(
            element = BPMN_START_EVENT,
            mode = ElementConstraintMode.TIMER_EXPRESSION,
        ),
        nlp = nlp,
        severity = RuleSeverity.ERROR,
    )
}
